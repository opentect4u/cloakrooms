package com.synergic.cloakroom.domain.repository

/** A cloak room operator (md_customer) as the super admin sees it. */
data class Customer(
    val id: Long,
    val name: String,
    val address: String,
    val state: String?,
    val pinCode: String,
    val mobileNo: String,
    val email: String?,
    val gstNo: String?,
    val upiId: String?,
    /** Latest registration end date (YYYY-MM-DD); null = no registration yet. Not editable. */
    val renewalDate: String?,
    val userLimit: Int,
    val active: Boolean,
    val remarks: String?,
)

/** Add / edit customer form. Blank optional fields are left out of the request. */
data class CustomerInput(
    val name: String,
    val address: String,
    val state: String,
    val pinCode: String,
    val mobileNo: String,
    val email: String,
    val gstNo: String,
    val upiId: String,
    val userLimit: Int,
    val active: Boolean,
    val remarks: String,
)

data class CustomerSummary(
    val customer: Customer,
    val usersTotal: Int,
    val usersAllowed: Int,
    val admins: Int,
    val operators: Int,
    val activeSessions: Int,
    val latestRegistration: Registration?,
    val paymentsCount: Int,
    /** Money as the server sends it, e.g. "5900.00". */
    val paymentsTotal: String,
    val lastPaymentDate: String?,
    val bagsTotal: Int,
    val bagsInStore: Int,
)

/** A subscription period; the latest end date is the customer's renewal date. */
data class Registration(
    val id: Long,
    val regNo: String,
    val startDate: String,
    val endDate: String,
    val userCount: Int,
    val remarks: String?,
)

data class RegistrationInput(
    val regNo: String,
    val startDate: String,
    val endDate: String,
    val userCount: Int,
    val remarks: String,
)

/** A user of a customer (md_user) in the super admin's Users list. */
data class ManagedUser(
    val id: Long,
    val userId: String,
    val userName: String,
    val userCode: String,
    /** A (admin) or O (counter user). */
    val userType: String,
    val allowed: Boolean,
    /** UTC db text while logged in, else null. */
    val sessionStartedAt: String?,
    val lastLogin: String?,
    /** Login shift in force today (counter users); null = may log in any time. */
    val shiftId: Long? = null,
    /** e.g. "Morning (06:00–14:00)". */
    val shiftLabel: String? = null,
)

data class NewUserInput(
    val userId: String,
    val userName: String,
    val userCode: String,
    val userType: String,
    val password: String,
    /** Login shift for a counter user; null = any time. */
    val shiftId: Long? = null,
)

/**
 * Super admin (user type S) work, online only (API_GUIDE.md §5): customers, their registrations
 * (which set the renewal date) and their users. Every call needs a super admin session
 * ([com.synergic.cloakroom.domain.auth.NotAuthorizedException] otherwise); refusals from the
 * server come as [com.synergic.cloakroom.domain.remote.ServerException].
 */
interface SuperAdminRepository {
    suspend fun customers(search: String?): List<Customer>
    suspend fun summary(customerId: Long): CustomerSummary
    suspend fun createCustomer(input: CustomerInput): Customer
    suspend fun updateCustomer(customerId: Long, input: CustomerInput): Customer

    suspend fun registrations(customerId: Long): List<Registration>
    suspend fun addRegistration(customerId: Long, input: RegistrationInput): Registration

    suspend fun users(customerId: Long): List<ManagedUser>
    /** The customer's shifts, for the Login shift choice. */
    suspend fun shifts(customerId: Long): List<ShiftItem>
    suspend fun addUser(customerId: Long, input: NewUserInput): ManagedUser
    /** Name, user code, type, and login shift (from today; admins have none). */
    suspend fun updateUser(customerId: Long, id: Long, userName: String, userCode: String, userType: String, shiftId: Long?)
    suspend fun setUserAllowed(userId: Long, allowed: Boolean): ManagedUser
    suspend fun resetPassword(userId: Long, newPassword: String)
    /** Returns the number of sessions ended. */
    suspend fun forceLogout(userId: Long): Int
}
