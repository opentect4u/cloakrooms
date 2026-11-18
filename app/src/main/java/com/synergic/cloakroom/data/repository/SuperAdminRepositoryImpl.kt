package com.synergic.cloakroom.data.repository

import com.synergic.cloakroom.data.remote.CustomerDto
import com.synergic.cloakroom.data.remote.MastersApi
import com.synergic.cloakroom.data.remote.RegistrationDto
import com.synergic.cloakroom.data.remote.SuperAdminApi
import com.synergic.cloakroom.data.remote.UserDto
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.UserTypes
import com.synergic.cloakroom.domain.repository.Customer
import com.synergic.cloakroom.domain.repository.CustomerInput
import com.synergic.cloakroom.domain.repository.CustomerSummary
import com.synergic.cloakroom.domain.repository.ManagedUser
import com.synergic.cloakroom.domain.repository.NewUserInput
import com.synergic.cloakroom.domain.repository.Registration
import com.synergic.cloakroom.domain.repository.RegistrationInput
import com.synergic.cloakroom.domain.repository.ShiftItem
import com.synergic.cloakroom.domain.repository.SuperAdminRepository
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuperAdminRepositoryImpl @Inject constructor(
    private val api: SuperAdminApi,
    private val masters: MastersApi,
    private val sessionProvider: SessionProvider,
    private val time: TimeSource,
) : SuperAdminRepository {

    private fun requireSuperAdmin() {
        if (sessionProvider.requireLogin().isSuperAdmin.not()) throw NotAuthorizedException("Only the super admin can do this.")
    }

    /**
     * Users and their login shifts: the super admin for any customer, the station admin for their
     * own (API_GUIDE.md §4 "Roles: A (own customer) and S"; the server checks the customer too).
     */
    private fun requireUserManager(customerId: Long? = null) {
        val s = sessionProvider.requireLogin()
        val ok = s.isSuperAdmin || (s.isAdmin && (customerId == null || s.customerId == customerId))
        if (!ok) throw NotAuthorizedException("Only an admin can manage users.")
    }

    override suspend fun customers(search: String?): List<Customer> {
        requireSuperAdmin()
        return api.listCustomers(search).items.map { it.toCustomer() }
    }

    override suspend fun summary(customerId: Long): CustomerSummary {
        requireSuperAdmin()
        val s = api.customerSummary(customerId)
        return CustomerSummary(
            customer = s.customer.toCustomer(),
            usersTotal = s.users.total,
            usersAllowed = s.users.allowed,
            admins = s.users.admins,
            operators = s.users.operators,
            activeSessions = s.users.activeSessions,
            latestRegistration = s.latestRegistration?.toRegistration(),
            paymentsCount = s.payments.count,
            paymentsTotal = s.payments.totalNet,
            lastPaymentDate = s.payments.lastPaymentDt,
            bagsTotal = s.luggage.total,
            bagsInStore = s.luggage.inStore,
        )
    }

    override suspend fun createCustomer(input: CustomerInput): Customer {
        requireSuperAdmin()
        return api.createCustomer(input.toBody()).toCustomer()
    }

    override suspend fun updateCustomer(customerId: Long, input: CustomerInput): Customer {
        requireSuperAdmin()
        return api.updateCustomer(customerId, input.toBody()).toCustomer()
    }

    override suspend fun registrations(customerId: Long): List<Registration> {
        requireSuperAdmin()
        return api.listRegistrations(customerId).items.map { it.toRegistration() }.sortedByDescending { it.endDate }
    }

    override suspend fun addRegistration(customerId: Long, input: RegistrationInput): Registration {
        requireSuperAdmin()
        val body = buildJsonObject {
            put("reg_no", input.regNo.trim())
            put("start_date", input.startDate.trim())
            put("end_date", input.endDate.trim())
            put("user_count", input.userCount)
            putIfNotBlank("remarks", input.remarks)
        }
        return api.createRegistration(customerId, body).toRegistration()
    }

    override suspend fun users(customerId: Long): List<ManagedUser> {
        requireUserManager(customerId)
        val shifts = shifts(customerId).associateBy { it.id }
        val today = today()
        // The assignment in force: the latest one whose start date is not after today.
        val current = masters.listShiftAssignments(customerId).filter { it.effectiveFrom <= today }
            .groupBy { it.userId }.mapValues { (_, rows) -> rows.maxBy { it.effectiveFrom } }
        return api.listUsers(customerId).items.map { u ->
            val shift = current[u.id]?.let { shifts[it.shiftId] }?.takeIf { u.userType == UserTypes.OPERATOR }
            u.toManagedUser().copy(shiftId = shift?.id, shiftLabel = shift?.let { "${it.name} (${it.fromTime}–${it.toTime})" })
        }.sortedWith(compareBy({ it.userType }, { it.userCode }))
    }

    override suspend fun shifts(customerId: Long): List<ShiftItem> {
        requireUserManager(customerId)
        return masters.listShifts(customerId).map { ShiftItem(it.shiftId, it.shiftName, it.fromTime.take(5), it.toTime.take(5)) }
    }

    override suspend fun updateUser(customerId: Long, id: Long, userName: String, userCode: String, userType: String, shiftId: Long?) {
        requireUserManager(customerId)
        api.updateUser(id, buildJsonObject {
            put("user_name", userName.trim())
            put("user_code", userCode.trim().uppercase())
            put("user_type", userType)
        })
        assignShift(customerId, id, if (userType == UserTypes.OPERATOR) shiftId else null)
    }

    /**
     * Login shift from today (td_assign_shift), as the admin's Users screen does: null removes
     * the user's assignments; the same day changes today's row; another day adds a row.
     */
    private suspend fun assignShift(customerId: Long, userDbId: Long, shiftId: Long?) {
        val today = today()
        val rows = masters.listShiftAssignments(customerId).filter { it.userId == userDbId }
        if (shiftId == null) {
            rows.forEach { masters.deleteShiftAssignment(it.id) }
            return
        }
        val current = rows.filter { it.effectiveFrom <= today }.maxByOrNull { it.effectiveFrom }
        when {
            current?.shiftId == shiftId -> Unit
            current != null && current.effectiveFrom == today -> masters.updateShiftAssignment(current.id, shiftId, today)
            else -> masters.createShiftAssignment(customerId, userDbId, shiftId, today)
        }
    }

    private fun today(): String = DateTimes.localDateText(time.now())

    override suspend fun addUser(customerId: Long, input: NewUserInput): ManagedUser {
        requireUserManager(customerId)
        val body = buildJsonObject {
            put("user_id", input.userId.trim())
            put("user_name", input.userName.trim())
            put("user_code", input.userCode.trim().uppercase())
            put("password", input.password)
            put("user_type", input.userType)
            put("customer_id", customerId)
        }
        val created = api.createUser(body)
        if (input.userType == UserTypes.OPERATOR && input.shiftId != null) assignShift(customerId, created.id, input.shiftId)
        return created.toManagedUser()
    }

    override suspend fun setUserAllowed(userId: Long, allowed: Boolean): ManagedUser {
        requireUserManager()
        return api.setAllow(userId, allowed).toManagedUser()
    }

    override suspend fun resetPassword(userId: Long, newPassword: String) {
        requireUserManager()
        api.resetPassword(userId, newPassword)
    }

    /** Super Admin only: a station admin cannot force-log-out users. */
    override suspend fun forceLogout(userId: Long): Int {
        requireSuperAdmin()
        return api.forceLogout(userId)
    }

    private fun CustomerInput.toBody(): JsonObject = buildJsonObject {
        put("customer_name", name.trim())
        put("customer_addr", address.trim())
        putIfNotBlank("state", state)
        put("pin_code", pinCode.trim())
        put("mobile_no", mobileNo.trim())
        putIfNotBlank("email", email)
        putIfNotBlank("gst_no", gstNo.uppercase())
        putIfNotBlank("upi_id", upiId)
        put("user_limit", userLimit)
        put("active_flag", if (active) "Y" else "N")
        putIfNotBlank("remarks", remarks)
    }

    private fun JsonObjectBuilder.putIfNotBlank(key: String, value: String) {
        if (value.isNotBlank()) put(key, value.trim())
    }

    private fun CustomerDto.toCustomer() = Customer(
        id = customerId,
        name = customerName,
        address = customerAddr,
        state = state,
        pinCode = pinCode,
        mobileNo = mobileNo,
        email = email,
        gstNo = gstNo,
        upiId = upiId,
        renewalDate = renewalDt,
        userLimit = userLimit,
        active = activeFlag == "Y",
        remarks = remarks,
    )

    private fun RegistrationDto.toRegistration() = Registration(regId, regNo, startDate, endDate, userCount, remarks)

    private fun UserDto.toManagedUser() = ManagedUser(
        id = id,
        userId = userId,
        userName = userName,
        userCode = userCode.orEmpty(),
        userType = userType,
        allowed = allowFlag == "Y",
        // The server sends IST times; screens expect UTC database text.
        sessionStartedAt = sessionStartedAt?.let { DateTimes.fromServerText(it) },
        lastLogin = lastLogin?.let { DateTimes.fromServerText(it) },
    )
}
