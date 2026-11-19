package com.synergic.cloakroom.data.repository

import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.LuggageInOutEntity
import com.synergic.cloakroom.data.numbering.UserCounterSequence
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.billing.BillCalculator
import com.synergic.cloakroom.domain.billing.ZERO_MONEY
import com.synergic.cloakroom.domain.billing.toMoney
import com.synergic.cloakroom.domain.billing.withTariff
import com.synergic.cloakroom.domain.checkin.CheckinField
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.checkin.CheckinValidation
import com.synergic.cloakroom.domain.checkin.CheckinValidator
import com.synergic.cloakroom.domain.device.DeviceInfo
import com.synergic.cloakroom.domain.numbering.CounterType
import com.synergic.cloakroom.domain.print.CheckinReceipt
import com.synergic.cloakroom.domain.print.CheckinReceiptFormatter
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.qr.ReceiptQrData
import com.synergic.cloakroom.domain.report.CheckinRow
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.IdProof
import com.synergic.cloakroom.domain.repository.LuggageRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.repository.SubscriptionRepository
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.shift.ShiftResolver
import com.synergic.cloakroom.domain.shift.ShiftSlot
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LuggageRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val sessionProvider: SessionProvider,
    private val settingsRepository: SettingsRepository,
    private val sequence: UserCounterSequence,
    private val deviceInfo: DeviceInfo,
    private val time: TimeSource,
    private val tariffs: TariffLookup,
    private val subscription: SubscriptionRepository,
) : LuggageRepository {

    override fun observeIdProofs(): Flow<List<IdProof>> =
        db.idProofDao().observeActive().map { list -> list.map { IdProof(it.idProofId, it.idProofName, it.maskFlag == "Y") } }

    override fun observeBagsInStore(): Flow<List<CheckinRow>> = flow {
        sessionProvider.requireLogin()
        val codes = db.userDao().getAll(Tenant.CUSTOMER_ID).associate { it.id to it.userCode.orEmpty() }
        emitAll(
            db.luggageDao().observeInStore(Tenant.CUSTOMER_ID).map { rows ->
                rows.map {
                    CheckinRow(
                        transId = it.transId,
                        receiptNo = it.receiptNo,
                        dateTimeIn = it.dateTimeIn,
                        userCode = codes[it.userIdIn].orEmpty(),
                        name = it.pasnName,
                        mobile = it.pasnMobNo,
                        pnr = it.pnrNo,
                        bags = it.luggageQty,
                        extraBags = it.extraLugageQty,
                        isOut = false,
                        advAmt = it.advAmt.toMoney(),
                    )
                }
            }
        )
    }

    override suspend fun checkIn(input: CheckinInput): CheckinResult {
        val session = sessionProvider.requireLogin()
        val settings = settingsRepository.getSettings(session.id)
        if (!settings.canWorkCounter(session.userType)) throw NotAuthorizedException("Admins do not check in or check out. A counter user must do it.")
        subscription.requireActive()

        val masked = input.idProofId?.let { db.idProofDao().get(it) }?.maskFlag == "Y"
        val data = when (val v = CheckinValidator.validate(input.copy(idProofMasked = masked), settings)) {
            is CheckinValidation.Invalid -> return CheckinResult.Invalid(v.errors)
            is CheckinValidation.Valid -> v.data
        }
        if (data.idProofId != CheckinValidator.NO_ID_PROOF) {
            val proof = db.idProofDao().get(data.idProofId)
            if (proof == null || proof.isDeleted == "Y") {
                return CheckinResult.Invalid(mapOf(CheckinField.ID_PROOF to "Choose the ID proof type."))
            }
        }

        val now = time.now()
        val nowText = DateTimes.toDbText(now)
        val transId = UUID.randomUUID().toString()
        // Advance (ADVANCE_FLAG = Y): the bill for ADVANCE_HOURS with today's price ranges, paid now in cash.
        val advance = advanceAt(now, data.bags, settings)
        val receiptNo = db.withTransaction {
            val number = sequence.next(session.id, session.userCode, CounterType.RECEIPT, settings.receiptReset, now, session.userId)
            db.luggageDao().insert(
                LuggageInOutEntity(
                    transId = transId,
                    customerId = Tenant.CUSTOMER_ID,
                    receiptNo = number.formatted,
                    receiptDt = DateTimes.localDateText(now),
                    userIdIn = session.id,
                    deviceIdIn = deviceInfo.deviceId(),
                    shiftIdIn = currentShift(now),
                    dateTimeIn = nowText,
                    pasnName = data.name,
                    pasnMobNo = data.mobile,
                    pnrNo = data.pnr,
                    luggageQty = data.bags,
                    extraLugageQty = data.extraBags,
                    advAmt = advance.toDouble(),
                    advHours = if (advance.signum() > 0) settings.advanceHours else 0,
                    // ID type and number are stored together or not at all (API_GUIDE.md §6.2).
                    idProofId = data.idProofId.takeIf { it != CheckinValidator.NO_ID_PROOF && data.idNumber.isNotEmpty() },
                    idNumber = data.idNumber.takeIf { data.idProofId != CheckinValidator.NO_ID_PROOF && it.isNotEmpty() },
                    createdBy = session.userId, createdAt = nowText, updatedBy = session.userId, updatedAt = nowText,
                )
            )
            number.formatted
        }
        return CheckinResult.Saved(transId, receiptNo)
    }

    override suspend fun checkinReceipt(transId: String): PrintDocument? {
        val session = sessionProvider.requireLogin()
        val row = db.luggageDao().get(transId)?.takeIf { it.customerId == Tenant.CUSTOMER_ID } ?: return null
        val settings = settingsRepository.getSettings(session.id)
        val customer = db.customerDao().get(Tenant.CUSTOMER_ID)
        val user = db.userDao().getById(row.userIdIn)
        val all = db.headerFooterDao().getActive(Tenant.CUSTOMER_ID).filter { it.inFlag == "Y" }
        val lines = all.filter { it.lineType != "L" && !it.narration.isNullOrBlank() }
        val receipt = CheckinReceipt(
            customerName = customer?.customerName.orEmpty(),
            headerLines = lines.filter { it.lineType == "H" }.map { it.narration!! },
            footerLines = lines.filter { it.lineType == "F" }.map { it.narration!! },
            userCode = user?.userCode.orEmpty(),
            userName = user?.userName.orEmpty(),
            idProofName = row.idProofId?.let { db.idProofDao().get(it) }?.idProofName,
            idNumber = row.idNumber.orEmpty(),
            logo = all.firstOrNull { it.lineType == "L" && it.image != null }?.image,
            qr = ReceiptQrData(
                transId = row.transId,
                receiptNo = row.receiptNo,
                receiptDt = row.receiptDt,
                customerId = row.customerId,
                userIdIn = row.userIdIn,
                dateTimeIn = row.dateTimeIn,
                bags = row.luggageQty,
                name = row.pasnName,
                mobile = row.pasnMobNo,
                pnr = row.pnrNo,
                extraBags = row.extraLugageQty,
                advAmt = row.advAmt.toMoney(),
            ),
        )
        return CheckinReceiptFormatter.format(receipt, settings)
    }

    override suspend fun advanceQuote(bags: Int): BigDecimal {
        val session = sessionProvider.requireLogin()
        if (bags < 1) return ZERO_MONEY
        return advanceAt(time.now(), bags, settingsRepository.getSettings(session.id))
    }

    private suspend fun advanceAt(now: Instant, bags: Int, settings: AppSettings): BigDecimal {
        if (!settings.advanceFlag) return ZERO_MONEY
        val today = DateTimes.localDateText(now)
        return BillCalculator.advance(now, bags, settings.withTariff(tariffs.on(today, today)))
    }

    /** Shift by the local clock time (md_shift ranges); null when no shift covers it. */
    private suspend fun currentShift(at: Instant): Long? {
        val shifts = db.shiftDao().getActive(Tenant.CUSTOMER_ID).map { ShiftSlot(it.shiftId, it.fromTime, it.toTime) }
        return ShiftResolver.shiftAt(shifts, at.atZone(DateTimes.DISPLAY_ZONE).toLocalTime())
    }
}
