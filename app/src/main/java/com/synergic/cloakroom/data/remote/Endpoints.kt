package com.synergic.cloakroom.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** §3 authentication. An interface so the auth repository can be tested without a server. */
interface AuthApi {
    /** POST /auth/login (public). */
    suspend fun login(request: LoginRequestDto): LoginDataDto

    /** GET /auth/me. */
    suspend fun me(): MeDto

    /** POST /auth/logout (also allowed after a force logout). */
    suspend fun logout()

    /** POST /auth/change-password; returns must_change_pwd (N). The token stays valid. */
    suspend fun changePassword(request: ChangePasswordRequestDto): String
}

@Singleton
class HttpAuthApi @Inject constructor(private val api: ApiClient) : AuthApi {
    override suspend fun login(request: LoginRequestDto): LoginDataDto =
        api.post("/auth/login", ApiClient.json.encodeToJsonElement(LoginRequestDto.serializer(), request), LoginDataDto.serializer(), auth = false)

    override suspend fun me(): MeDto = api.get("/auth/me", MeDto.serializer())

    override suspend fun logout() {
        api.post("/auth/logout", null, JsonElement.serializer())
    }

    override suspend fun changePassword(request: ChangePasswordRequestDto): String =
        api.post("/auth/change-password", ApiClient.json.encodeToJsonElement(ChangePasswordRequestDto.serializer(), request), ChangePasswordDataDto.serializer())
            .mustChangePwd
}

/** Super admin (S) endpoints of §5: customers, registrations, users of a customer. */
@Singleton
class SuperAdminApi @Inject constructor(private val api: ApiClient) {

    /** GET /customers (search on name etc., newest first by default). */
    suspend fun listCustomers(search: String?, page: Int = 1, limit: Int = MAX_LIMIT): Page<CustomerDto> =
        api.getPage("/customers", CustomerDto.serializer(), mapOf("search" to search?.ifBlank { null }, "page" to page, "limit" to limit))

    /** POST /customers → 201 (also creates default settings and rate). */
    suspend fun createCustomer(body: JsonObject): CustomerDto = api.post("/customers", body, CustomerDto.serializer())

    /** PATCH /customers/:id (any create field). */
    suspend fun updateCustomer(id: Long, body: JsonObject): CustomerDto = api.patch("/customers/$id", body, CustomerDto.serializer())

    /** GET /customers/:id/summary. */
    suspend fun customerSummary(id: Long): CustomerSummaryDto = api.get("/customers/$id/summary", CustomerSummaryDto.serializer())

    /** GET /customers/:customerId/registrations. */
    suspend fun listRegistrations(customerId: Long): Page<RegistrationDto> =
        api.getPage("/customers/$customerId/registrations", RegistrationDto.serializer(), mapOf("limit" to MAX_LIMIT))

    /** POST /customers/:customerId/registrations (recomputes the customer's renewal_dt). */
    suspend fun createRegistration(customerId: Long, body: JsonObject): RegistrationDto =
        api.post("/customers/$customerId/registrations", body, RegistrationDto.serializer())

    /** GET /users?customer_id= (S must name the customer). */
    suspend fun listUsers(customerId: Long): Page<UserDto> =
        api.getPage("/users", UserDto.serializer(), mapOf("customer_id" to customerId, "limit" to MAX_LIMIT))

    /** POST /users (customer_id required for A/O users created by S). */
    suspend fun createUser(body: JsonObject): UserDto = api.post("/users", body, UserDto.serializer())

    /** PATCH /users/:id: user_name, user_code, user_type (A/O). */
    suspend fun updateUser(id: Long, body: JsonObject): UserDto = api.patch("/users/$id", body, UserDto.serializer())

    /** PATCH /users/:id/allow { allow_flag } (N also force-logs out). */
    suspend fun setAllow(id: Long, allowed: Boolean): UserDto =
        api.patch("/users/$id/allow", buildJsonObject { put("allow_flag", if (allowed) "Y" else "N") }, UserDto.serializer())

    /** POST /users/:id/reset-password { password } (also force-logs out). */
    suspend fun resetPassword(id: Long, password: String): Int =
        api.post("/users/$id/reset-password", buildJsonObject { put("password", password) }, SessionsEndedDto.serializer()).sessionsEnded

    /** POST /users/:id/force-logout. */
    suspend fun forceLogout(id: Long): Int =
        api.post("/users/$id/force-logout", null, SessionsEndedDto.serializer()).sessionsEnded

    private companion object {
        /** §2: lists allow at most 200 per page. */
        const val MAX_LIMIT = 200
    }
}
