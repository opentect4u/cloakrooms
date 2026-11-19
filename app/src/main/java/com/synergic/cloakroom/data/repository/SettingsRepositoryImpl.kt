package com.synergic.cloakroom.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.data.db.entity.SettingLogEntity
import com.synergic.cloakroom.data.security.PasswordHasher
import com.synergic.cloakroom.di.ApplicationScope
import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PrinterType
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.settings.SettingDataType
import com.synergic.cloakroom.domain.settings.AdvanceRule
import com.synergic.cloakroom.domain.settings.SettingDefinition
import com.synergic.cloakroom.domain.settings.SettingValidator
import com.synergic.cloakroom.domain.settings.SettingsResolver
import com.synergic.cloakroom.domain.settings.SettingsValidationException
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val sessionProvider: SessionProvider,
    private val hasher: PasswordHasher,
    private val time: TimeSource,
    @ApplicationScope scope: CoroutineScope,
) : SettingsRepository {

    private val settingDao get() = db.settingDao()

    /**
     * Follows the session: customer-wide values when logged out, the user's values (with their
     * override rows) when logged in. Room re-emits on every md_setting change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val currentSettings: StateFlow<AppSettings?> = sessionProvider.state
        .map { (it as? SessionState.LoggedIn)?.session?.id }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            settingDao.observeScopedRows(Tenant.CUSTOMER_ID, userId).map { rows ->
                runCatching { toAppSettings(rows, userId) }
                    .onFailure { Log.e("SettingsRepository", "Settings cannot be read", it) }
                    .getOrNull()
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun observeSettings(userId: Long?): Flow<AppSettings> =
        settingDao.observeScopedRows(Tenant.CUSTOMER_ID, userId)
            .map { toAppSettings(it, userId) }
            .distinctUntilChanged()

    override suspend fun getSettings(userId: Long?): AppSettings =
        toAppSettings(settingDao.getScopedRows(Tenant.CUSTOMER_ID, userId), userId)

    override fun observeDefinitions(): Flow<List<SettingDefinition>> =
        settingDao.observeScopedRows(Tenant.CUSTOMER_ID, null)
            .map { rows -> rows.sortedBy { it.sortOrder }.map { it.toDefinition() } }

    override suspend fun updateSettings(changes: Map<String, String>): Int {
        val admin = sessionProvider.requireAdmin()
        return db.withTransaction {
            val rows = settingDao.getScopedRows(Tenant.CUSTOMER_ID, null).associateBy { it.settingName }
            val definitions = rows.values.map { it.toDefinition() }
            val dataTypes = definitions.associate { it.name to it.dataType }

            // An empty PASSWORD field means "keep the current password", so it is not a change.
            // Advance hours always follows the 1st price range; a typed value is replaced.
            val requested = AdvanceRule.apply(definitions, changes.mapValues { (name, value) ->
                if (dataTypes[name] == SettingDataType.SLABS) RateSlabs.canonical(value.trim()) else value
            })
                .mapValues { (name, value) ->
                    when (dataTypes[name]) {
                        SettingDataType.PASSWORD -> value
                        // Price ranges are saved in the server's JSON form, whatever the editor sent.
                        SettingDataType.SLABS -> RateSlabs.canonical(value.trim())
                        else -> value.trim()
                    }
                }
                .filter { (name, value) ->
                    val row = rows[name] ?: return@filter true
                    if (dataTypes[name] == SettingDataType.PASSWORD) value.isNotEmpty() && !hasher.verify(value, row.settingValue)
                    else value != row.settingValue
                }

            val errors = SettingValidator.validateChanges(definitions, requested)
            if (errors.isNotEmpty()) throw SettingsValidationException(errors)

            val now = DateTimes.toDbText(time.now())
            requested.forEach { (name, value) ->
                val row = rows.getValue(name)
                val isPassword = dataTypes[name] == SettingDataType.PASSWORD
                val stored = if (isPassword) hasher.hash(value) else value
                settingDao.update(
                    row.copy(
                        settingValue = stored,
                        syncFlag = "N",
                        localVersion = row.localVersion + 1,
                        updatedBy = admin.userId,
                        updatedAt = now,
                    )
                )
                settingDao.insertLog(
                    SettingLogEntity(
                        customerId = Tenant.CUSTOMER_ID,
                        settingName = name,
                        // Never write a password or its hash to the log.
                        oldValue = if (isPassword) mask(row.settingValue) else row.settingValue,
                        newValue = if (isPassword) mask(stored) else stored,
                        changedBy = admin.userId,
                        changedAt = now,
                        createdBy = admin.userId, createdAt = now, updatedBy = admin.userId, updatedAt = now,
                    )
                )
            }
            requested.size
        }
    }

    override fun observePrintDefinitions(): Flow<List<SettingDefinition>> {
        val session = sessionProvider.current
        val userId = session?.id
        return settingDao.observeScopedRows(Tenant.CUSTOMER_ID, userId).map { raw ->
            val rows = withoutAdminSetOverrides(raw)
            val effective = SettingsResolver.resolve(rows, userId, { it.settingName }, { it.userId })
            // Screen details from the customer row (kept up to date), value from the user's own row if any.
            rows.filter { it.userId == null && it.settingType == PRINT_GROUP }
                .sortedBy { it.sortOrder }
                .map { row ->
                    val def = row.toDefinition().copy(value = effective[row.settingName]?.settingValue ?: row.settingValue)
                    // Counter users see admin-set Print settings (e.g. Print QR) but cannot change them.
                    if (session?.isAdmin != true && row.settingName in SettingKeys.ADMIN_SET_PRINT) {
                        def.copy(readOnly = true, readOnlyNote = ADMIN_SET_NOTE)
                    } else def
                }
        }
    }

    /** User override rows of [SettingKeys.ADMIN_SET_PRINT] never apply: the admin's value is for everyone. */
    private fun withoutAdminSetOverrides(rows: List<SettingEntity>): List<SettingEntity> =
        rows.filterNot { it.userId != null && it.settingName in SettingKeys.ADMIN_SET_PRINT }

    override suspend fun updatePrintSettings(changes: Map<String, String>): Int {
        val session = sessionProvider.requireLogin()
        if (session.isAdmin) return updateSettings(changes)
        return db.withTransaction {
            val rows = settingDao.getScopedRows(Tenant.CUSTOMER_ID, session.id)
            val customerRows = rows.filter { it.userId == null }.associateBy { it.settingName }
            val ownRows = rows.filter { it.userId == session.id }.associateBy { it.settingName }
            if (changes.keys.any { customerRows[it]?.settingType != PRINT_GROUP }) {
                throw NotAuthorizedException("Counter users can change only the Print settings.")
            }
            changes.keys.firstOrNull { it in SettingKeys.ADMIN_SET_PRINT }?.let { name ->
                val label = customerRows[name]?.label ?: name
                throw NotAuthorizedException("\"$label\" is set by the admin.")
            }
            val requested = changes
                .mapValues { it.value.trim() }
                .filter { (name, value) -> value != (ownRows[name] ?: customerRows.getValue(name)).settingValue }
            val errors = SettingValidator.validateChanges(customerRows.values.map { it.toDefinition() }, requested)
            if (errors.isNotEmpty()) throw SettingsValidationException(errors)

            val now = DateTimes.toDbText(time.now())
            requested.forEach { (name, value) ->
                val customerRow = customerRows.getValue(name)
                val ownRow = ownRows[name]
                if (ownRow == null) {
                    settingDao.insert(
                        customerRow.copy(
                            settingId = 0, userId = session.id, settingValue = value, syncFlag = "N", localVersion = 1,
                            createdBy = session.userId, createdAt = now, updatedBy = session.userId, updatedAt = now,
                        )
                    )
                } else {
                    settingDao.update(
                        ownRow.copy(
                            settingValue = value, syncFlag = "N", localVersion = ownRow.localVersion + 1,
                            updatedBy = session.userId, updatedAt = now,
                        )
                    )
                }
                settingDao.insertLog(
                    SettingLogEntity(
                        customerId = Tenant.CUSTOMER_ID,
                        settingName = name,
                        oldValue = (ownRow ?: customerRow).settingValue,
                        newValue = value,
                        changedBy = session.userId,
                        changedAt = now,
                        createdBy = session.userId, createdAt = now, updatedBy = session.userId, updatedAt = now,
                    )
                )
            }
            requested.size
        }
    }

    override suspend fun savePrinterChoice(printerType: PrinterType, btPrinter: String): Int =
        updatePrintSettings(mapOf(SettingKeys.PRINTER_TYPE to printerType.code, SettingKeys.BT_PRINTER to btPrinter.trim()))

    private fun mask(value: String): String = if (value.isEmpty()) "" else "******"

    private companion object {
        /** setting_type of the Print tab: the only settings counter users may change (for themselves). */
        const val PRINT_GROUP = "P"

        /** Shown under an admin-set Print setting on a counter user's Settings screen. */
        const val ADMIN_SET_NOTE = "Set by the admin. You can only see it."
    }

    private fun toAppSettings(rows: List<SettingEntity>, userId: Long?): AppSettings {
        val effective = SettingsResolver.resolve(withoutAdminSetOverrides(rows), userId, { it.settingName }, { it.userId })
        return AppSettings.fromValues(effective.mapValues { it.value.settingValue })
    }
}

/** A md_setting row as the Settings screen shows and validates it. */
internal fun SettingEntity.toDefinition() = SettingDefinition(
    name = settingName,
    value = settingValue,
    group = settingType,
    label = label ?: settingName,
    dataType = SettingDataType.entries.firstOrNull { it.name == dataType } ?: SettingDataType.TEXT,
    options = SettingDefinition.parseOptions(options),
    minValue = minValue,
    maxValue = maxValue,
    dependsOn = dependsOn,
    sortOrder = sortOrder,
    description = description,
)
