package com.synergic.cloakroom.data.repository

import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.LuggageInOutEntity
import com.synergic.cloakroom.data.db.entity.ReceiptEntity
import com.synergic.cloakroom.data.numbering.UserCounterSequence
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.billing.BillCalculationException
import com.synergic.cloakroom.domain.billing.BillCalculator
import com.synergic.cloakroom.domain.billing.Tariff
import com.synergic.cloakroom.domain.billing.withTariff
import com.synergic.cloakroom.domain.billing.toMoney
import com.synergic.cloakroom.domain.checkout.CheckoutField
import com.synergic.cloakroom.domain.checkout.CheckoutInput
import com.synergic.cloakroom.domain.checkout.CheckoutValidation
import com.synergic.cloakroom.domain.checkout.CheckoutValidator
import com.synergic.cloakroom.domain.device.DeviceInfo
import com.synergic.cloakroom.domain.numbering.CounterType
import com.synergic.cloakroom.domain.print.BillFormatter
import com.synergic.cloakroom.domain.print.BillPrint
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.qr.ReceiptQr
import com.synergic.cloakroom.domain.repository.BillRepository
import com.synergic.cloakroom.domain.repository.CheckoutBag
import com.synergic.cloakroom.domain.repository.CheckoutRequest
import com.synergic.cloakroom.domain.repository.CheckoutResult
import com.synergic.cloakroom.domain.repository.QrLookup
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.shift.ShiftResolver
import com.synergic.cloakroom.domain.shift.ShiftSlot
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import com.synergic.cloakroom.domain.upi.UpiLink
import com.synergic.cloakroom.domain.upi.UpiPayee
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val sessionProvider: SessionProvider,
    private val settingsRepository: SettingsRepository,
    private val sequence: UserCounterSequence,
    private val deviceInfo: DeviceInfo,
    private val time: TimeSource,
) : BillRepository {

    override suspend fun search(query: String): List<CheckoutBag> {
        sessionProvider.requireLogin()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.luggageDao().search(Tenant.CUSTOMER_ID, "%$escaped%", SEARCH_LIMIT).map { toBag(it) }
    }

    override suspend fun find(transId: String): CheckoutBag? {
        sessionProvider.requireLogin()
        return db.luggageDao().get(transId)?.takeIf { it.customerId == Tenant.CUSTOMER_ID && it.isDeleted == "N" }?.let { toBag(it) }
    }

    override suspend fun lookupQr(text: String): QrLookup {
        sessionProvider.requireLogin()
        val qr = ReceiptQr.decode(text) ?: return QrLookup.NotAReceipt
        if (qr.customerId != Tenant.CUSTOMER_ID) return QrLookup.OtherCustomer
        find(qr.transId)?.let { return QrLookup.Found(it) }
        // Checked in on another counter that has not synced yet: bill it from the QR data.
        return QrLookup.Found(
            CheckoutBag(
                transId = qr.transId,
                receiptNo = qr.receiptNo,
                receiptDt = qr.receiptDt,
                userIdIn = qr.userIdIn,
                dateTimeIn = qr.dateTimeIn,
                name = qr.name,
                mobile = qr.mobile,
                pnr = qr.pnr,
                bags = qr.bags,
                extraBags = qr.extraBags,
                idProofName = null,
                idNumber = "",
                isOut = false,
                billNo = null,
                fromQr = qr,
            )
        )
    }

    override suspend fun upiPayee(): UpiPayee? {
        sessionProvider.requireLogin()
        val c = db.customerDao().get(Tenant.CUSTOMER_ID) ?: return null
        return c.upiId?.takeIf { it.isNotBlank() }?.let { UpiPayee(it, c.customerName) }
    }

    override suspend fun tariff(receiptDt: String): Tariff {
        sessionProvider.requireLogin()
        return tariffOn(receiptDt, DateTimes.localDateText(time.now()))
    }

    private suspend fun tariffOn(receiptDt: String, billDt: String): Tariff {
        val rate = db.rateDao().rateOn(Tenant.CUSTOMER_ID, receiptDt)
        // Used only when the cloak room has no own GST % (withTariff).
        val gst = db.gstDao().gstOn(billDt)
        return Tariff(
            blockHours = rate?.blockHours,
            firstBlockRate = rate?.firstBlockRate?.toMoney(),
            nextBlockRate = rate?.nextBlockRate?.toMoney(),
            cgstPct = gst?.cgstPct?.toMoney(),
            sgstPct = gst?.sgstPct?.toMoney(),
            igstPct = gst?.igstPct?.toMoney(),
        )
    }

    override suspend fun checkOut(request: CheckoutRequest): CheckoutResult {
        val session = sessionProvider.requireLogin()
        val baseSettings = settingsRepository.getSettings(session.id)
        if (!baseSettings.canWorkCounter(session.userType)) throw NotAuthorizedException("Admins do not check in or check out. A counter user must do it.")
        val now = time.now()
        val nowText = DateTimes.toDbText(now)
        val billDt = DateTimes.localDateText(now)
        val transId = request.bag.transId
        // §7: the rate of the check-in date and the GST of the bill date (server rows from login /
        // pull); a cloak room's own GST % replaces the GST row.
        val settings = baseSettings.withTariff(tariffOn(request.bag.receiptDt, billDt))

        return db.withTransaction<CheckoutResult> {
            val existing = db.luggageDao().get(transId)
            val validation = CheckoutValidator.validate(
                CheckoutInput(
                    alreadyOut = existing?.outFlag == "Y" || db.receiptDao().get(transId) != null,
                    penalty = request.penalty,
                    payMode = request.payMode,
                ),
                settings,
            )
            val valid = when (validation) {
                is CheckoutValidation.Invalid -> return@withTransaction CheckoutResult.Invalid(validation.errors)
                is CheckoutValidation.Valid -> validation
            }

            val bill = try {
                BillCalculator.calculate(
                    checkIn = DateTimes.fromDbText(existing?.dateTimeIn ?: request.bag.dateTimeIn),
                    checkOut = now,
                    bags = existing?.luggageQty ?: request.bag.bags,
                    penalty = valid.penalty,
                    settings = settings,
                )
            } catch (e: BillCalculationException) {
                return@withTransaction CheckoutResult.Invalid(mapOf(CheckoutField.PENALTY to e.message!!))
            }
            if (bill.netAmt.compareTo(request.expectedNet) != 0) {
                return@withTransaction CheckoutResult.Invalid(
                    mapOf(CheckoutField.AMOUNT to "The bill is now Rs.${bill.netAmt.toPlainString()} (a new block started). Please check and confirm again.")
                )
            }

            // Case 1: a bag checked in on another counter is first copied in from its QR,
            // keeping its trans_id, receipt number and check-in user (no new receipt number).
            if (existing == null) {
                val qr = request.bag.fromQr ?: return@withTransaction CheckoutResult.Invalid(
                    mapOf(CheckoutField.BAG to "This bag was not found.")
                )
                db.luggageDao().insert(
                    LuggageInOutEntity(
                        transId = qr.transId,
                        customerId = qr.customerId,
                        receiptNo = qr.receiptNo,
                        receiptDt = qr.receiptDt,
                        userIdIn = qr.userIdIn,
                        // Not in the QR: left empty; the check-in device's push fills them (backend A8).
                        deviceIdIn = null,
                        dateTimeIn = qr.dateTimeIn,
                        pasnName = qr.name,
                        pasnMobNo = qr.mobile,
                        pnrNo = qr.pnr,
                        luggageQty = qr.bags,
                        extraLugageQty = qr.extraBags,
                        idProofId = null,
                        idNumber = null,
                        createdBy = session.userId, createdAt = nowText, updatedBy = session.userId, updatedAt = nowText,
                    )
                )
            }

            val gstNo = if (bill.gstFlag) db.customerDao().get(Tenant.CUSTOMER_ID)?.gstNo else null
            val shiftId = currentShift(now)
            val updated = db.luggageDao().markOut(transId, session.id, deviceInfo.deviceId(), shiftId, nowText, session.userId)
            if (updated != 1) {
                return@withTransaction CheckoutResult.Invalid(mapOf(CheckoutField.BAG to "This bag is already checked out."))
            }

            val number = sequence.next(session.id, session.userCode, CounterType.BILL, settings.receiptReset, now, session.userId)
            db.receiptDao().insert(
                ReceiptEntity(
                    transId = transId,
                    customerId = Tenant.CUSTOMER_ID,
                    billNo = number.formatted,
                    billDt = billDt,
                    userId = session.id,
                    deviceId = deviceInfo.deviceId(),
                    shiftId = shiftId,
                    luggageQty = bill.bags,
                    daysCharged = bill.blocks,
                    firstBlockRate = bill.firstBlockRate.toDouble(),
                    nextBlockRate = bill.nextBlockRate.toDouble(),
                    blockHours = bill.blockHours,
                    baseAmt = bill.baseAmt.toDouble(),
                    penalAmt = bill.penaltyAmt.toDouble(),
                    netAmt = bill.netAmt.toDouble(),
                    taxableAmt = bill.taxableAmt.toDouble(),
                    gstPct = bill.gstPct.toDouble(),
                    cgst = bill.cgst.toDouble(),
                    sgst = bill.sgst.toDouble(),
                    igst = bill.igst.toDouble(),
                    gstFlag = if (bill.gstFlag) "Y" else "N",
                    // The options billed with: the server recomputes with them (§7).
                    graceMinutes = bill.graceMinutes,
                    gstInclusive = if (bill.gstInclusive) "Y" else "N",
                    penaltyGstFlag = if (bill.penaltyGstFlag) "Y" else "N",
                    payMode = request.payMode.code,
                    billPrinted = if (request.printBill) "Y" else "N",
                    gstNo = gstNo,
                    createdBy = session.userId, createdAt = nowText, updatedBy = session.userId, updatedAt = nowText,
                )
            )
            CheckoutResult.Saved(transId, number.formatted, bill.netAmt)
        }
    }

    override suspend fun billDocument(transId: String): PrintDocument? {
        val session = sessionProvider.requireLogin()
        val r = db.receiptDao().get(transId)?.takeIf { it.customerId == Tenant.CUSTOMER_ID } ?: return null
        val inOut = db.luggageDao().get(transId) ?: return null
        val customer = db.customerDao().get(Tenant.CUSTOMER_ID)
        val user = db.userDao().getById(r.userId)
        val all = db.headerFooterDao().getActive(Tenant.CUSTOMER_ID).filter { it.outFlag == "Y" }
        val lines = all.filter { it.lineType != "L" && !it.narration.isNullOrBlank() }
        val print = BillPrint(
            customerName = customer?.customerName.orEmpty(),
            gstNo = r.gstNo, // as printed on the original bill
            headerLines = lines.filter { it.lineType == "H" }.map { it.narration!! },
            footerLines = lines.filter { it.lineType == "F" }.map { it.narration!! },
            billNo = r.billNo,
            receiptNo = inOut.receiptNo,
            dateTimeIn = inOut.dateTimeIn,
            dateTimeOut = inOut.dateTimeOut ?: r.createdAt.orEmpty(),
            userCode = user?.userCode.orEmpty(),
            userName = user?.userName.orEmpty(),
            name = inOut.pasnName,
            mobile = inOut.pasnMobNo,
            pnr = inOut.pnrNo,
            bags = r.luggageQty,
            extraBags = inOut.extraLugageQty,
            blocks = r.daysCharged,
            blockHours = r.blockHours,
            firstBlockRate = r.firstBlockRate.toMoney(),
            nextBlockRate = r.nextBlockRate.toMoney(),
            baseAmt = r.baseAmt.toMoney(),
            penaltyAmt = r.penalAmt.toMoney(),
            taxableAmt = r.taxableAmt.toMoney(),
            gstFlag = r.gstFlag == "Y",
            gstPct = r.gstPct.toMoney(),
            cgst = r.cgst.toMoney(),
            sgst = r.sgst.toMoney(),
            igst = r.igst.toMoney(),
            netAmt = r.netAmt.toMoney(),
            payMode = if (r.payMode == PayMode.UPI.code) PayMode.UPI else PayMode.CASH,
            logo = all.firstOrNull { it.lineType == "L" && it.image != null }?.image,
            // Same link as the QR shown at check-out.
            upiLink = if (r.payMode == PayMode.UPI.code) {
                customer?.upiId?.takeIf { it.isNotBlank() }?.let {
                    UpiLink.build(UpiPayee(it, customer.customerName), r.netAmt.toMoney(), "Cloak room ${inOut.receiptNo}")
                }
            } else null,
        )
        return BillFormatter.format(print, settingsRepository.getSettings(session.id).paperWidthMm)
    }

    private suspend fun toBag(row: LuggageInOutEntity): CheckoutBag = CheckoutBag(
        transId = row.transId,
        receiptNo = row.receiptNo,
        receiptDt = row.receiptDt,
        userIdIn = row.userIdIn,
        dateTimeIn = row.dateTimeIn,
        name = row.pasnName,
        mobile = row.pasnMobNo,
        pnr = row.pnrNo,
        bags = row.luggageQty,
        extraBags = row.extraLugageQty,
        idProofName = row.idProofId?.let { db.idProofDao().get(it) }?.idProofName,
        idNumber = row.idNumber.orEmpty(),
        isOut = row.outFlag == "Y",
        billNo = db.receiptDao().get(row.transId)?.billNo,
    )

    private suspend fun currentShift(at: Instant): Long? {
        val shifts = db.shiftDao().getActive(Tenant.CUSTOMER_ID).map { ShiftSlot(it.shiftId, it.fromTime, it.toTime) }
        return ShiftResolver.shiftAt(shifts, at.atZone(DateTimes.DISPLAY_ZONE).toLocalTime())
    }

    private companion object {
        const val SEARCH_LIMIT = 50
    }
}
