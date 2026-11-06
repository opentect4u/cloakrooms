package com.synergic.cloakroom.data.repository

import androidx.room.withTransaction
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.data.db.entity.SettingLogEntity
import com.synergic.cloakroom.data.remote.GstDto
import com.synergic.cloakroom.data.remote.MastersApi
import com.synergic.cloakroom.data.remote.RateDto
import com.synergic.cloakroom.data.seed.SeedData
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.repository.CustomerSettingsRepository
import com.synergic.cloakroom.domain.repository.ServerSettingKey
import com.synergic.cloakroom.domain.repository.ServerSettingKind
import com.synergic.cloakroom.domain.settings.SettingDataType
import com.synergic.cloakroom.domain.settings.SettingDefinition
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.settings.SettingValidator
import com.synergic.cloakroom.domain.settings.SettingsValidationException
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One customer's settings on the server: the super admin for any customer, the station admin for
 * their own (same screen, same rules; the server takes an admin's customer from the token). The keys
 * of API_GUIDE.md §8 are read from and saved to the server (`?customer_id=` / body customer_id);
 * the three tariff fields of the Billing tab are the customer's rate (/rates); GST % is the
 * customer's own setting (shown from the shared GST table /gst until one is saved); only the Print
 * tab keys without an API are kept for that customer in this phone's md_setting. Labels, types,
 * limits and tabs come from [SeedData].
 */
@Singleton
class CustomerSettingsRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val api: MastersApi,
    private val sessionProvider: SessionProvider,
    private val time: TimeSource,
) : CustomerSettingsRepository {

    private val serverKeys = ServerSettingKey.entries.associateBy { it.name }

    private fun requireEditor(customerId: Long): UserSession {
        val session = sessionProvider.requireLogin()
        if (!session.isSuperAdmin && !(session.isAdmin && session.customerId == customerId)) {
            throw NotAuthorizedException("Only an admin can change the settings.")
        }
        return session
    }

    override fun isOnServer(name: String): Boolean = name in serverKeys || name in RATE_KEYS

    /** The GST row in force today (latest effective_from on or before today), else the newest one. */
    private suspend fun currentGst(today: String): GstDto? {
        val rows = api.listGst().filter { it.isDeleted == "N" }.sortedByDescending { it.effectiveFrom }
        return rows.firstOrNull { it.effectiveFrom.take(10) <= today } ?: rows.firstOrNull()
    }

    /** The rate in force today (latest effective_from on or before today), else the newest one. */
    private suspend fun currentRate(customerId: Long, today: String): RateDto? {
        val rates = api.listRates(customerId).sortedByDescending { it.effectiveFrom }
        return rates.firstOrNull { it.effectiveFrom <= today } ?: rates.firstOrNull()
    }

    private fun today(): String = DateTimes.localDateText(time.now())

    override suspend fun definitions(customerId: Long): List<SettingDefinition> {
        val session = requireEditor(customerId)
        return buildDefinitions(customerId).map { def ->
            if (!session.isSuperAdmin && def.name in SettingKeys.SUPER_ADMIN_ONLY) def.copy(readOnly = true) else def
        }
    }

    private suspend fun buildDefinitions(customerId: Long): List<SettingDefinition> {
        val server = api.listSettings(customerId).filter { it.userId == null }.associateBy { it.settingName }
        val local = db.settingDao().getScopedRows(customerId, null).filter { it.userId == null }.associateBy { it.settingName }
        val rate = currentRate(customerId, today())
        // Default for GST % when the customer has no own value: the shared GST table in force today.
        val sharedGst = currentGst(today())?.let { g ->
            val gstType = server[SettingKeys.GST_TYPE]?.settingValue ?: ServerSettingKey.GST_TYPE.default
            plainPct(gstTotal(g, gstType))
        }

        val fromSeed = SeedData.settings.mapIndexed { i, s ->
            val key = serverKeys[s.name]
            val base = SettingDefinition(
                name = s.name,
                value = s.value,
                group = s.type,
                label = s.label,
                dataType = SettingDataType.valueOf(s.dataType),
                options = SettingDefinition.parseOptions(s.options),
                minValue = s.min,
                maxValue = s.max,
                dependsOn = s.dependsOn,
                sortOrder = (i + 1) * 10,
                description = s.description,
            )
            if (key != null && s.name == SettingKeys.GST_PCT && server[s.name] == null) {
                // No own GST % yet: show the shared GST rate that bills use now.
                serverDefinition(base, key, sharedGst ?: key.default, null)
                    .copy(description = listOfNotNull(s.description, "Not set yet: bills use the shared GST rate").joinToString(" · "))
            } else if (key != null) {
                serverDefinition(base, key, server[s.name]?.settingValue, server[s.name]?.settingType)
            } else if (s.name in RATE_KEYS) {
                // The tariff lives in /rates on the server (md_rate_dtls), not in /settings.
                base.copy(
                    value = when (s.name) {
                        SettingKeys.FIRST_BLOCK_RATE -> rate?.firstBlockRate
                        SettingKeys.NEXT_BLOCK_RATE -> rate?.nextBlockRate
                        else -> rate?.blockHours?.toString()
                    } ?: s.value,
                    maxValue = if (s.name == SettingKeys.BLOCK_HOURS) MAX_BLOCK_HOURS else s.max,
                    description = listOfNotNull(
                        s.description,
                        "Saved on the server as the rate from today" + (rate?.let { " (now: from ${it.effectiveFrom})" } ?: ""),
                    ).joinToString(" · "),
                )
            } else {
                base.copy(
                    value = local[s.name]?.settingValue ?: s.value,
                    description = listOfNotNull(s.description, PHONE_ONLY).joinToString(" · "),
                )
            }
        }
        // §8 keys the app's own list does not have (none today; FORCE_ONLINE_DAYS is not used).
        val extra = ServerSettingKey.entries.filter { k -> SeedData.settings.none { it.name == k.name } }.mapIndexed { i, key ->
            val base = SettingDefinition(
                name = key.name, value = key.default, group = "G", label = key.label, dataType = SettingDataType.TEXT,
                options = emptyList(), minValue = null, maxValue = null, dependsOn = null,
                sortOrder = 10_000 + i, description = key.help,
            )
            serverDefinition(base, key, server[key.name]?.settingValue, server[key.name]?.settingType)
        }
        return fromSeed + extra
    }

    /** Server keys use the server's value and the limits of §8; the tab follows the app's list. */
    private fun serverDefinition(base: SettingDefinition, key: ServerSettingKey, value: String?, type: String?): SettingDefinition {
        val kind = key.kind
        return base.copy(
            value = value ?: key.default,
            // Tabs as on the admin's Settings screen (the server may file a key elsewhere, e.g.
            // RECEIPT_RESET as G); keys the app's list does not have use the server's tab.
            group = if (SeedData.settings.any { it.name == key.name }) base.group else type ?: base.group,
            dataType = when (kind) {
                ServerSettingKind.YesNo -> SettingDataType.BOOL
                is ServerSettingKind.Number -> SettingDataType.INT
                is ServerSettingKind.Decimal -> SettingDataType.DECIMAL
                is ServerSettingKind.Choice -> SettingDataType.ENUM
                ServerSettingKind.Password -> SettingDataType.PASSWORD
            },
            options = (kind as? ServerSettingKind.Choice)?.options ?: base.options,
            minValue = (kind as? ServerSettingKind.Number)?.min?.toDouble() ?: (kind as? ServerSettingKind.Decimal)?.min ?: base.minValue,
            maxValue = (kind as? ServerSettingKind.Number)?.max?.toDouble() ?: (kind as? ServerSettingKind.Decimal)?.max ?: base.maxValue,
        )
    }

    override suspend fun save(customerId: Long, changes: Map<String, String>): Int {
        val session = requireEditor(customerId)
        // Hiding the switch is not enough: refuse super-admin-only settings from anyone else.
        if (!session.isSuperAdmin && changes.keys.any { it in SettingKeys.SUPER_ADMIN_ONLY }) {
            throw NotAuthorizedException("Only Synergic (super admin) can change this setting.")
        }
        val by = session.userId
        val definitions = definitions(customerId)
        val typed = changes.mapValues { (name, value) ->
            if (definitions.firstOrNull { it.name == name }?.dataType == SettingDataType.PASSWORD) value else value.trim()
        }
        val errors = SettingValidator.validateChanges(definitions, typed)
        if (errors.isNotEmpty()) throw SettingsValidationException(errors)
        // Money keys travel as 2-decimal strings ("500.00"), as the API sends them.
        val requested = typed.mapValues { (name, value) ->
            if (serverKeys[name]?.kind is ServerSettingKind.Decimal) money(value) else value
        }

        // Server keys first: a refusal stops before anything is written on this phone.
        val serverRows = api.listSettings(customerId).filter { it.userId == null }.associateBy { it.settingName }
        requested.filterKeys { it in serverKeys }.forEach { (name, value) ->
            try {
                val row = serverRows[name]
                if (row == null) api.createSetting(customerId, name, value) else api.updateSetting(row.settingId, value)
            } catch (e: ServerException) {
                if (e.code == ServerException.VALIDATION_ERROR) {
                    throw SettingsValidationException(mapOf(name to (e.fieldErrors.values.firstOrNull() ?: e.message.orEmpty())))
                }
                throw e
            }
        }

        // Tariff: a rate from today (or today's rate updated); older rates stay for older bags.
        val rateChanges = requested.filterKeys { it in RATE_KEYS }
        if (rateChanges.isNotEmpty()) {
            val today = today()
            val current = currentRate(customerId, today)
            val first = money(rateChanges[SettingKeys.FIRST_BLOCK_RATE] ?: current?.firstBlockRate ?: seedValue(SettingKeys.FIRST_BLOCK_RATE))
            val next = money(rateChanges[SettingKeys.NEXT_BLOCK_RATE] ?: current?.nextBlockRate ?: seedValue(SettingKeys.NEXT_BLOCK_RATE))
            val hours = (rateChanges[SettingKeys.BLOCK_HOURS] ?: current?.blockHours?.toString() ?: seedValue(SettingKeys.BLOCK_HOURS)).toInt()
            try {
                if (current != null && current.effectiveFrom == today) api.updateRate(current.rateId, hours, first, next, today)
                else api.createRate(customerId, hours, first, next, today)
            } catch (e: ServerException) {
                if (e.code == ServerException.VALIDATION_ERROR) {
                    throw SettingsValidationException(mapOf(rateChanges.keys.first() to (e.fieldErrors.values.firstOrNull() ?: e.message.orEmpty())))
                }
                throw e
            }
        }

        // The station admin's own phone uses the new values at once (billing reads md_setting);
        // for the super admin only the keys without an API are kept here.
        val isServerKey = { name: String -> name in serverKeys || name in RATE_KEYS }
        val localChanges = if (session.isSuperAdmin) requested.filterKeys { !isServerKey(it) } else requested
        if (localChanges.isNotEmpty()) {
            val now = DateTimes.toDbText(time.now())
            db.withTransaction {
                val rows = db.settingDao().getScopedRows(customerId, null).filter { it.userId == null }.associateBy { it.settingName }
                localChanges.forEach { (name, value) ->
                    val row = rows[name]
                    val seed = SeedData.settings.firstOrNull { it.name == name } ?: return@forEach
                    val syncFlag = if (isServerKey(name)) "Y" else "N"
                    if (row == null) {
                        db.settingDao().insert(
                            SettingEntity(
                                customerId = customerId, userId = null, settingName = name, settingValue = value,
                                settingType = seed.type, label = seed.label, dataType = seed.dataType, options = seed.options,
                                minValue = seed.min, maxValue = seed.max, dependsOn = seed.dependsOn,
                                sortOrder = (SeedData.settings.indexOf(seed) + 1) * 10, description = seed.description,
                                syncFlag = syncFlag, createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                            )
                        )
                    } else {
                        db.settingDao().update(row.copy(settingValue = value, syncFlag = syncFlag, localVersion = row.localVersion + 1, updatedBy = by, updatedAt = now))
                    }
                    db.settingDao().insertLog(
                        SettingLogEntity(
                            customerId = customerId, settingName = name, oldValue = row?.settingValue ?: seed.value, newValue = value,
                            changedBy = by, changedAt = now, createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                        )
                    )
                }
            }
        }
        return requested.size
    }

    private fun gstTotal(g: GstDto, gstType: String): java.math.BigDecimal =
        if (gstType == "I") g.igstPct.toBigDecimal() else g.cgstPct.toBigDecimal() + g.sgstPct.toBigDecimal()

    /** "18.00" → "18". */
    private fun plainPct(p: java.math.BigDecimal): String = p.stripTrailingZeros().toPlainString()

    private fun seedValue(name: String) = SeedData.settings.first { it.name == name }.value

    /** "60" → "60.00", as the API sends money. */
    private fun money(text: String): String = text.trim().toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    private companion object {
        const val PHONE_ONLY = "Saved on this phone only (no API yet)"

        /** Billing tab fields saved as the customer's rate (/rates), API_GUIDE.md §5. */
        val RATE_KEYS = setOf(SettingKeys.FIRST_BLOCK_RATE, SettingKeys.NEXT_BLOCK_RATE, SettingKeys.BLOCK_HOURS)

        /** /rates block_hours limit. */
        const val MAX_BLOCK_HOURS = 720.0
    }
}
