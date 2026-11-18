package com.synergic.cloakroom.data.seed

import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.CustomerEntity
import com.synergic.cloakroom.data.db.entity.HeaderFooterEntity
import com.synergic.cloakroom.data.db.entity.IdProofEntity
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.data.db.entity.ShiftEntity
import com.synergic.cloakroom.data.db.entity.UserEntity
import com.synergic.cloakroom.data.security.PasswordHasher
import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Writes [SeedData] into an empty database (first launch) and resets it in debug builds. */
@Singleton
class DemoSeeder @Inject constructor(
    private val db: AppDatabase,
    private val hasher: PasswordHasher,
    private val time: TimeSource,
) {
    private val mutex = Mutex()

    /**
     * Run at every app start: seeds an empty database, adds customer-wide setting rows that were
     * added to [SeedData] in a later version, removes the rows of retired settings, and brings the screen details of existing rows
     * (label, options, help text, limits) up to date. Setting values are never changed.
     */
    suspend fun ensureSeeded() {
        seedIfEmpty()
        addMissingSettings()
    }

    /**
     * Inserts the seed setting rows whose name is not in md_setting yet for the current customer
     * ([Tenant.CUSTOMER_ID]; also called after a login of another customer). Returns how many.
     */
    suspend fun addMissingSettings(): Int = mutex.withLock {
        db.withTransaction {
            val now = DateTimes.toDbText(time.now())
            splitPrintCopies(now)
            mergeRateSettings(now)
            val existing = db.settingDao().getScopedRows(Tenant.CUSTOMER_ID, null).map { it.settingName }.toSet()
            val missing = SeedData.settings.withIndex()
                .filter { it.value.name !in existing }
                .map { settingRow(it.index, it.value, now) }
            if (missing.isNotEmpty()) db.settingDao().insertAll(missing)
            db.settingDao().retire(SeedData.retiredSettings, Tenant.SYSTEM_USER, now)
            refreshDefinitions(now)
            missing.size
        }
    }

    /**
     * PRINT_COPIES became CHECKIN_PRINT_COPIES and CHECKOUT_PRINT_COPIES (2026-11-08). Every
     * active PRINT_COPIES row (customer-wide and user overrides) is copied into both new settings
     * with the same value, unless that scope already has the new row; the old row is retired
     * afterwards. Returns how many rows were added.
     */
    private suspend fun splitPrintCopies(now: String): Int {
        val newNames = listOf(SettingKeys.CHECKIN_PRINT_COPIES, SettingKeys.CHECKOUT_PRINT_COPIES)
        val rows = db.settingDao().getActiveByNames(listOf(SeedData.LEGACY_PRINT_COPIES) + newNames)
        val have = rows.map { Triple(it.customerId, it.userId, it.settingName) }.toSet()
        val seeds = SeedData.settings.associateBy { it.name }
        val added = rows.filter { it.settingName == SeedData.LEGACY_PRINT_COPIES }.flatMap { old ->
            newNames.withIndex()
                .filter { (_, name) -> Triple(old.customerId, old.userId, name) !in have }
                .map { (i, name) ->
                    val s = seeds.getValue(name)
                    old.copy(
                        settingId = 0, settingName = name, label = s.label, dataType = s.dataType, options = s.options,
                        minValue = s.min, maxValue = s.max, dependsOn = s.dependsOn, description = s.description,
                        sortOrder = old.sortOrder + i * 5, syncFlag = "N", localVersion = 1,
                        createdBy = Tenant.SYSTEM_USER, createdAt = now, updatedBy = Tenant.SYSTEM_USER, updatedAt = now,
                    )
                }
        }
        if (added.isNotEmpty()) db.settingDao().insertAll(added)
        return added.size
    }

    /**
     * FIRST_BLOCK_RATE / NEXT_BLOCK_RATE / BLOCK_HOURS became the price ranges RATE_SLABS
     * (2026-11-18). Each scope that still has the old rows gets a RATE_SLABS row with the same
     * tariff (0–B h first rate, B–2B h next rate, so bills stay the same); the old rows are retired
     * afterwards. Returns how many rows were added.
     */
    private suspend fun mergeRateSettings(now: String): Int {
        val legacy = listOf(SettingKeys.FIRST_BLOCK_RATE, SettingKeys.NEXT_BLOCK_RATE, SettingKeys.BLOCK_HOURS)
        val rows = db.settingDao().getActiveByNames(legacy + SettingKeys.RATE_SLABS)
        val seed = SeedData.settings.first { it.name == SettingKeys.RATE_SLABS }
        val added = rows.filter { it.settingName != SettingKeys.RATE_SLABS }.groupBy { it.customerId to it.userId }
            .filterKeys { (customer, user) -> rows.none { it.settingName == SettingKeys.RATE_SLABS && it.customerId == customer && it.userId == user } }
            .map { (_, scope) ->
                val values = scope.associate { it.settingName to it.settingValue.trim() }
                val hours = values[SettingKeys.BLOCK_HOURS]?.toIntOrNull()?.takeIf { it >= 1 }
                val first = values[SettingKeys.FIRST_BLOCK_RATE]?.toBigDecimalOrNull()
                val next = values[SettingKeys.NEXT_BLOCK_RATE]?.toBigDecimalOrNull()
                val tariff = if (hours != null && first != null && next != null) {
                    RateSlabs.toJson(RateSlabs.fromTwoRate(hours, first, next))
                } else {
                    seed.value
                }
                val template = scope.first()
                template.copy(
                    settingId = 0, settingName = seed.name, settingValue = tariff, settingType = seed.type, label = seed.label,
                    dataType = seed.dataType, options = seed.options, minValue = seed.min, maxValue = seed.max,
                    dependsOn = seed.dependsOn, description = seed.description,
                    sortOrder = SeedData.sortOrderOf(SeedData.settings.indexOf(seed), seed), syncFlag = "N", localVersion = 1,
                    createdBy = Tenant.SYSTEM_USER, createdAt = now, updatedBy = Tenant.SYSTEM_USER, updatedAt = now,
                )
            }
        if (added.isNotEmpty()) db.settingDao().insertAll(added)
        return added.size
    }

    /**
     * Updates label, data type, options, limits, depends_on and description of the customer-wide
     * rows from [SeedData] where they differ (e.g. a new ENUM choice). Value and sort order stay.
     * Returns how many rows changed.
     */
    private suspend fun refreshDefinitions(now: String): Int {
        val seeds = SeedData.settings.associateBy { it.name }
        var changed = 0
        db.settingDao().getScopedRows(Tenant.CUSTOMER_ID, null).forEach { row ->
            val s = seeds[row.settingName] ?: return@forEach
            val updated = row.copy(
                label = s.label, dataType = s.dataType, options = s.options, minValue = s.min,
                maxValue = s.max, dependsOn = s.dependsOn, description = s.description,
            )
            if (updated != row) {
                db.settingDao().update(
                    updated.copy(syncFlag = "N", localVersion = row.localVersion + 1, updatedBy = Tenant.SYSTEM_USER, updatedAt = now)
                )
                changed++
            }
        }
        return changed
    }

    /**
     * Seeds only a completely empty database (first launch, or after "Reset demo data"). A device
     * that already holds a customer — demo data or one copied from a server login — is never
     * seeded again, whatever [Tenant.CUSTOMER_ID] says. Returns true if it seeded.
     */
    suspend fun seedIfEmpty(): Boolean = mutex.withLock {
        if (db.customerDao().count() > 0) return@withLock false
        // Hash outside the transaction: PBKDF2 is slow on purpose and would hold the write lock.
        val hashes = withContext(Dispatchers.Default) {
            SeedData.users.associate { it.userId to hasher.hash(it.firstPassword) }
        }
        db.withTransaction {
            if (db.customerDao().count() == 0) insertAll(hashes)
        }
        true
    }

    /** Deletes every row and seeds again. Only reachable from the debug menu. */
    suspend fun resetDemoData() {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        seedIfEmpty()
    }

    private suspend fun insertAll(passwordHashes: Map<String, String>) {
        val now = DateTimes.toDbText(time.now())
        val by = Tenant.SYSTEM_USER
        val customerId = Tenant.CUSTOMER_ID

        SeedData.customer.let {
            db.customerDao().insert(
                CustomerEntity(
                    customerId = customerId,
                    customerName = it.name,
                    customerAddr = it.address,
                    pinCode = it.pinCode,
                    mobileNo = it.mobile,
                    email = it.email,
                    gstNo = it.gstNo,
                    upiId = it.upiId,
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                )
            )
        }

        SeedData.users.forEach {
            db.userDao().insert(
                UserEntity(
                    customerId = customerId,
                    userId = it.userId,
                    userName = it.userName,
                    userType = it.userType,
                    userCode = it.userCode,
                    passwordHash = passwordHashes.getValue(it.userId),
                    mustChangePwd = "Y",
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                )
            )
        }

        db.idProofDao().insertAll(
            SeedData.idProofs.map {
                IdProofEntity(
                    idProofName = it, maskFlag = if (it.startsWith("Aadhaar")) "Y" else "N",
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                )
            }
        )

        db.headerFooterDao().insertAll(
            SeedData.headerFooterLines.mapIndexed { i, (type, text) ->
                HeaderFooterEntity(
                    customerId = customerId, lineType = type, narration = text,
                    lineOrder = SeedData.headerFooterLines.take(i + 1).count { it.first == type },
                    inFlag = "Y", outFlag = "Y", reportFlag = if (type == "H") "Y" else "N",
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                )
            }
        )

        db.shiftDao().insertAll(
            SeedData.shifts.map { (name, from, to) ->
                ShiftEntity(
                    customerId = customerId, shiftName = name, fromTime = from, toTime = to,
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                )
            }
        )

        db.settingDao().insertAll(SeedData.settings.mapIndexed { index, s -> settingRow(index, s, now) })
    }

    private fun settingRow(index: Int, s: SeedData.SeedSetting, now: String) = SettingEntity(
        customerId = Tenant.CUSTOMER_ID,
        userId = null,
        settingName = s.name,
        settingValue = s.value,
        settingType = s.type,
        label = s.label,
        dataType = s.dataType,
        options = s.options,
        minValue = s.min,
        maxValue = s.max,
        dependsOn = s.dependsOn,
        sortOrder = SeedData.sortOrderOf(index, s),
        description = s.description,
        createdBy = Tenant.SYSTEM_USER,
        createdAt = now,
        updatedBy = Tenant.SYSTEM_USER,
        updatedAt = now,
    )
}
