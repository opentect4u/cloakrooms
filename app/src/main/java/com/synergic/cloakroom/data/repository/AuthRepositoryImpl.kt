package com.synergic.cloakroom.data.repository

import androidx.room.withTransaction
import com.synergic.cloakroom.BuildConfig
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.AssignShiftEntity
import com.synergic.cloakroom.data.db.entity.CustomerEntity
import com.synergic.cloakroom.data.db.entity.UserEntity
import com.synergic.cloakroom.data.remote.ApiClient
import com.synergic.cloakroom.data.remote.ApiConfig
import com.synergic.cloakroom.data.remote.AppReleaseDto
import com.synergic.cloakroom.data.remote.AuthApi
import com.synergic.cloakroom.data.remote.AuthEvents
import com.synergic.cloakroom.data.remote.ChangePasswordRequestDto
import com.synergic.cloakroom.data.remote.CustomerDto
import com.synergic.cloakroom.data.remote.LoginRequestDto
import com.synergic.cloakroom.data.remote.UserDto
import com.synergic.cloakroom.data.security.PasswordHasher
import com.synergic.cloakroom.data.seed.DemoSeeder
import com.synergic.cloakroom.data.session.SessionManager
import com.synergic.cloakroom.data.session.SessionStore
import com.synergic.cloakroom.data.session.TokenStore
import com.synergic.cloakroom.data.sync.ServerDataImporter
import com.synergic.cloakroom.data.sync.SyncManager
import com.synergic.cloakroom.di.ApplicationScope
import com.synergic.cloakroom.domain.auth.AuthException
import com.synergic.cloakroom.domain.auth.LoginResult
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.auth.UserSummary
import com.synergic.cloakroom.domain.auth.UserTypes
import com.synergic.cloakroom.domain.device.DeviceInfo
import com.synergic.cloakroom.domain.remote.Connectivity
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.repository.AuthRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.repository.ShiftItem
import com.synergic.cloakroom.domain.shift.ShiftResolver
import com.synergic.cloakroom.domain.shift.ShiftSlot
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Login, logout and session against the live API (API_GUIDE.md §3). The server checks password,
 * block, single session, subscription and user limit; the app keeps tokens in secure storage and
 * mirrors the logged-in user and customer into the local database for offline work.
 *
 * User management below still works on the local database until it moves to the API (step g).
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val hasher: PasswordHasher,
    private val time: TimeSource,
    private val deviceInfo: DeviceInfo,
    private val sessionStore: SessionStore,
    private val tokenStore: TokenStore,
    private val sessionManager: SessionManager,
    private val seeder: DemoSeeder,
    private val authApi: AuthApi,
    private val connectivity: Connectivity,
    authEvents: AuthEvents,
    @ApplicationScope private val scope: CoroutineScope,
    private val apiConfig: ApiConfig,
    private val importer: ServerDataImporter,
    private val syncManager: SyncManager,
) : AuthRepository {

    private val userDao get() = db.userDao()

    override val sessionState: StateFlow<SessionState> = sessionManager.state

    init {
        // The server ended the session (force logout, logged in elsewhere, revoked token).
        scope.launch {
            authEvents.sessionEnded.collect { message ->
                if (sessionManager.current != null) {
                    // §3.5: a force-logged-out device may still push; send what is waiting first.
                    val waiting = runCatching { syncManager.pushPending() }.getOrDefault(0)
                    clearLocalSession(if (waiting > 0) "$message ${waitingText(waiting)} will be sent at the next login." else message)
                }
            }
        }
        // 403 PASSWORD_CHANGE_REQUIRED on any call: show the forced change-password screen.
        scope.launch {
            authEvents.passwordChangeRequired.collect {
                val session = sessionManager.current ?: return@collect
                if (!session.mustChangePassword) setSession(session.copy(mustChangePassword = true))
            }
        }
    }

    override suspend fun restoreSession() {
        val saved = sessionStore.savedSession()
        // The customer of the saved session first: seeding and setting rows follow it.
        saved?.customerId?.let { Tenant.CUSTOMER_ID = it }
        seeder.ensureSeeded()
        if (saved == null || tokenStore.accessToken == null) {
            clearLocalSession()
            return
        }
        sessionManager.set(SessionState.LoggedIn(saved))
        // When online, ask the server whether the session is still alive; a 401 SESSION_ENDED
        // comes back through AuthEvents. Counter users offline simply continue.
        if (connectivity.isOnline()) {
            scope.launch { runCatching { authApi.me() } }
        }
    }

    override suspend fun login(userId: String, password: String): LoginResult {
        val loginId = userId.trim()
        if (loginId.isEmpty() || password.isEmpty()) return LoginResult.Failure("Enter your user ID and password.")

        val data = try {
            authApi.login(LoginRequestDto(loginId, password, deviceInfo.deviceId(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        } catch (e: ServerException) {
            if (e.code == ServerException.UPDATE_REQUIRED) return updateRequired(e)
            return LoginResult.Failure(loginMessage(e))
        }
        tokenStore.saveTokens(data.accessToken, data.refreshToken)
        val user = data.user
        val session = UserSession(
            id = user.id,
            userId = user.userId,
            userName = user.userName,
            userType = user.userType,
            userCode = user.userCode.orEmpty(),
            sessionStartedAt = data.serverTime,
            mustChangePassword = user.mustChangePwd == "Y",
            customerId = user.customerId,
        )

        if (user.userType != UserTypes.SUPER_ADMIN) {
            val customer = data.customer
            if (customer == null) {
                endServerSession()
                return LoginResult.Failure("This user has no cloak room. Ask Synergic to check the account.")
            }
            Tenant.CUSTOMER_ID = customer.customerId
            // A customer seen for the first time on this phone needs its setting rows (read just below).
            seeder.addMissingSettings()
            // What the Super Admin / Admin set up on the server replaces the phone's copy.
            importer.import(customer.customerId, user.id, data.masters, data.settings, data.counters, data.serverTime, complete = true)
            outsideLoginShift(user)?.let { message ->
                endServerSession()
                return LoginResult.Failure(message)
            }
            mirrorLocally(user, customer, data.serverTime)
        }

        // The first pull after a login brings everything again (all open bags of the customer).
        syncManager.resetCursor(session)
        sessionStore.save(session)
        sessionManager.set(SessionState.LoggedIn(session))
        return LoginResult.Success(session)
    }

    /**
     * Logout needs internet (your rule, §3.6): rows still waiting are pushed first. Offline, or
     * when the push does not get through, logout is refused with the number of rows waiting.
     */
    override suspend fun logout() {
        val session = sessionManager.current
        if (session != null && !session.isSuperAdmin && session.customerId != null) {
            val waiting = syncManager.pushPending()
            if (waiting > 0) {
                throw AuthException(
                    if (connectivity.isOnline()) "${waitingText(waiting)} could not be sent to the server. Try Logout again in a moment."
                    else "No internet. ${waitingText(waiting)} must be sent to the server before logout. Connect to the internet and try again."
                )
            }
        }
        if (session != null && connectivity.isOnline()) {
            runCatching { authApi.logout() }
        }
        clearLocalSession()
    }

    private fun waitingText(rows: Int) = if (rows == 1) "1 entry is waiting and" else "$rows entries are waiting and"


    override fun clearLocalSession(message: String?) {
        sessionStore.clear()
        tokenStore.clearTokens()
        sessionManager.set(SessionState.LoggedOut(message))
    }

    /** POST /auth/change-password (API_GUIDE.md §3). The same tokens keep working afterwards. */
    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        val session = sessionManager.requireLogin()
        when {
            currentPassword.isEmpty() -> throw AuthException("Enter your current password.")
            newPassword == currentPassword -> throw AuthException("The new password must be different from the current one.")
            newPassword.length !in 8..128 || newPassword.none(Char::isLetter) || newPassword.none(Char::isDigit) ->
                throw AuthException("The new password needs 8 to 128 characters with at least one letter and one digit.")
        }
        val stillMustChange = try {
            authApi.changePassword(ChangePasswordRequestDto(currentPassword, newPassword)) == "Y"
        } catch (e: ServerException) {
            throw AuthException(
                when {
                    e.code == ServerException.VALIDATION_ERROR && "current_password" in e.fieldErrors -> "Current password is wrong."
                    e.code == ServerException.VALIDATION_ERROR -> e.fieldErrors.values.firstOrNull() ?: e.message.orEmpty()
                    e.code == ServerException.NETWORK -> "Cannot reach the server. Changing the password needs internet."
                    e.code == ServerException.RATE_LIMITED -> "Too many tries. Wait a minute and try again."
                    else -> e.message ?: "Could not change the password."
                }
            )
        }
        setSession(session.copy(mustChangePassword = stillMustChange))
    }

    private fun setSession(session: UserSession) {
        sessionStore.save(session)
        sessionManager.set(SessionState.LoggedIn(session))
    }

    /** 426 UPDATE_REQUIRED: the latest release from errors.latest, with an absolute download link. */
    private fun updateRequired(e: ServerException): LoginResult.UpdateRequired {
        val latest = e.details["latest"]?.let {
            runCatching { ApiClient.json.decodeFromString(AppReleaseDto.serializer(), it) }.getOrNull()
        }
        val url = latest?.apkUrl?.let { if (it.startsWith("http")) it else apiConfig.baseUrl.trimEnd('/') + "/" + it.trimStart('/') }
        return LoginResult.UpdateRequired(latest?.versionName, url, latest?.releaseNotes)
    }

    /** POST /auth/logout after a login the app refuses, so the server session does not stay open. */
    private suspend fun endServerSession() {
        runCatching { authApi.logout() }
        tokenStore.clearTokens()
    }

    /** The logged-in user and their customer in the local database, as the server sent them. */
    private suspend fun mirrorLocally(user: UserDto, customer: CustomerDto, serverTime: String) {
        db.withTransaction {
            importer.importCustomer(customer, serverTime)
            val local = userDao.getById(user.id)
            userDao.upsert(
                UserEntity(
                    id = user.id,
                    customerId = user.customerId,
                    userId = user.userId,
                    userName = user.userName,
                    userType = user.userType,
                    allowFlag = user.allowFlag,
                    deviceId = deviceInfo.deviceId(),
                    sessionStartedAt = serverTime,
                    lastLogin = serverTime,
                    userCode = user.userCode,
                    // The server owns passwords; a hash from the offline phase is only kept as it was.
                    passwordHash = local?.passwordHash,
                    mustChangePwd = "N",
                    syncFlag = "Y",
                    localVersion = local?.localVersion ?: 1,
                    createdBy = local?.createdBy, createdAt = local?.createdAt,
                    updatedBy = Tenant.SYSTEM_USER, updatedAt = serverTime,
                )
            )
        }
    }

    /**
     * Counter users with a login shift may log in only during it (SHIFT_LOGIN_FLAG). The server
     * does not check this (audit gap G6), so the app does it after a successful server login.
     */
    private suspend fun outsideLoginShift(user: UserDto): String? {
        if (user.userType != UserTypes.OPERATOR) return null
        val settings = settingsRepository.getSettings(user.id)
        if (!settings.shiftLoginFlag) return null
        val at = time.now()
        val shift = currentShift(user.id, at) ?: return null
        val inside = ShiftResolver.isWithin(shift.slot, at.atZone(DateTimes.DISPLAY_ZONE).toLocalTime(), settings.shiftEarlyLoginMin)
        return if (inside) null else "You can log in only during your shift ${shift.label}."
    }

    private fun loginMessage(e: ServerException): String = when (e.code) {
        ServerException.NETWORK -> "Cannot reach the server. Logging in needs internet."
        ServerException.UNAUTHORIZED -> "Wrong user ID or password."
        ServerException.SESSION_ACTIVE -> {
            val since = e.details["session_started_at"]?.let { runCatching { DateTimes.displayText(it) }.getOrNull() }
            "Already logged in on another device${since?.let { " since $it" } ?: ""}. Logout there first, or ask an admin to force logout."
        }
        ServerException.SUBSCRIPTION_EXPIRED ->
            "The subscription ended${e.details["renewal_dt"]?.let { " on $it" } ?: ""}. Please contact Synergic."
        ServerException.USER_LIMIT_REACHED ->
            "All ${e.details["user_limit"] ?: "licensed"} user seats are in use. Ask your admin."
        ServerException.RATE_LIMITED -> "Too many login attempts. Wait a minute and try again."
        ServerException.VALIDATION_ERROR -> e.fieldErrors.values.firstOrNull() ?: e.message.orEmpty()
        else -> e.message ?: "Login failed."
    }

    // ---- Admin only ------------------------------------------------------------------------

    override fun observeUsers(): Flow<List<UserSummary>> = flow {
        sessionManager.requireAdmin()
        emitAll(
            combine(
                userDao.observeAll(Tenant.CUSTOMER_ID),
                db.assignShiftDao().observeActive(Tenant.CUSTOMER_ID),
                db.shiftDao().observeActive(Tenant.CUSTOMER_ID),
            ) { users, assignments, shifts ->
                val today = DateTimes.localDateText(time.now())
                // Rows are newest first: the first one not after today is the one in force.
                val current = assignments.filter { it.effectiveFrom <= today }.groupBy { it.userId }.mapValues { it.value.first() }
                val byId = shifts.associateBy { it.shiftId }
                users.map { u ->
                    val shift = current[u.id]?.let { byId[it.shiftId] }?.takeIf { u.userType == UserTypes.OPERATOR }
                    u.toSummary().copy(shiftId = shift?.shiftId, shiftLabel = shift?.let { "${it.shiftName} (${it.fromTime}–${it.toTime})" })
                }
            }
        )
    }

    override suspend fun shiftOptions(): List<ShiftItem> {
        sessionManager.requireAdmin()
        return db.shiftDao().getActive(Tenant.CUSTOMER_ID).map { ShiftItem(it.shiftId, it.shiftName, it.fromTime, it.toTime) }
    }

    override suspend fun addUser(userId: String, userName: String, userType: String, firstPassword: String, shiftId: Long?) {
        val admin = sessionManager.requireAdmin()
        val loginId = userId.trim().lowercase()
        if (!USER_ID_PATTERN.matches(loginId)) {
            throw AuthException("User ID must be 3–20 characters: letters, digits, dot or underscore.")
        }
        if (userName.isBlank()) throw AuthException("Enter the user's name.")
        checkUserType(userType)
        checkPasswordLength(firstPassword, userId = null)
        val hash = hasher.hash(firstPassword)

        db.withTransaction {
            if (userDao.countWithUserId(loginId) > 0) throw AuthException("User ID '$loginId' already exists.")
            val now = now()
            val newId = userDao.insert(
                UserEntity(
                    customerId = Tenant.CUSTOMER_ID,
                    userId = loginId,
                    userName = userName.trim(),
                    userType = userType,
                    userCode = nextUserCode(),
                    passwordHash = hash,
                    mustChangePwd = "Y",
                    createdBy = admin.userId, createdAt = now, updatedBy = admin.userId, updatedAt = now,
                )
            )
            assignShift(newId, if (userType == UserTypes.OPERATOR) shiftId else null, admin.userId)
        }
    }

    override suspend fun updateUser(id: Long, userName: String, userType: String, shiftId: Long?) {
        val admin = sessionManager.requireAdmin()
        if (userName.isBlank()) throw AuthException("Enter the user's name.")
        checkUserType(userType)
        db.withTransaction {
            val user = requireUser(id)
            if (user.id == admin.id && userType != user.userType) {
                throw AuthException("You cannot change your own user type.")
            }
            userDao.update(user.copy(userName = userName.trim(), userType = userType).touched(admin.userId, now()))
            assignShift(user.id, if (userType == UserTypes.OPERATOR) shiftId else null, admin.userId)
        }
    }

    override suspend fun setBlocked(id: Long, blocked: Boolean) {
        val admin = sessionManager.requireAdmin()
        if (id == admin.id) throw AuthException("You cannot block yourself.")
        db.withTransaction {
            val user = requireUser(id)
            val changed = if (blocked) user.copy(allowFlag = "N") else user.copy(allowFlag = "Y", failedAttempts = 0)
            userDao.update(changed.touched(admin.userId, now()))
        }
    }

    override suspend fun resetPassword(id: Long, newPassword: String) {
        val admin = sessionManager.requireAdmin()
        checkPasswordLength(newPassword, userId = id)
        val hash = hasher.hash(newPassword)
        db.withTransaction {
            val user = requireUser(id)
            userDao.update(
                user.copy(passwordHash = hash, mustChangePwd = "Y", failedAttempts = 0).touched(admin.userId, now())
            )
        }
    }

    override suspend fun forceLogout(id: Long) {
        val admin = sessionManager.requireAdmin()
        if (id == admin.id) throw AuthException("Use Logout to end your own session.")
        db.withTransaction {
            val user = requireUser(id)
            userDao.update(user.copy(deviceId = null, sessionStartedAt = null).touched(admin.userId, now()))
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** A user's login shift in force at [at], or null when there is none (or it was removed). */
    private class CurrentShift(val slot: ShiftSlot, val label: String)

    private suspend fun currentShift(userDbId: Long, at: Instant): CurrentShift? {
        val assignment = db.assignShiftDao().getCurrent(Tenant.CUSTOMER_ID, userDbId, DateTimes.localDateText(at)) ?: return null
        val shift = db.shiftDao().get(assignment.shiftId)?.takeIf { it.isDeleted == "N" } ?: return null
        return CurrentShift(ShiftSlot(shift.shiftId, shift.fromTime, shift.toTime), "${shift.shiftName} (${shift.fromTime}–${shift.toTime})")
    }

    /**
     * Sets the login shift from today (td_assign_shift, effective_from = today's local date).
     * Earlier rows stay as history. [shiftId] null = any time: all of the user's rows are removed.
     */
    private suspend fun assignShift(userDbId: Long, shiftId: Long?, by: String) {
        val dao = db.assignShiftDao()
        val now = now()
        val today = DateTimes.localDateText(time.now())
        if (shiftId == null) {
            dao.getActive(Tenant.CUSTOMER_ID, userDbId).forEach {
                dao.update(it.copy(isDeleted = "Y", syncFlag = "N", localVersion = it.localVersion + 1, updatedBy = by, updatedAt = now))
            }
            return
        }
        db.shiftDao().get(shiftId)?.takeIf { it.isDeleted == "N" && it.customerId == Tenant.CUSTOMER_ID }
            ?: throw AuthException("Choose a shift from the list.")
        val current = dao.getCurrent(Tenant.CUSTOMER_ID, userDbId, today)
        when {
            current?.shiftId == shiftId -> Unit
            current != null && current.effectiveFrom == today ->
                dao.update(current.copy(shiftId = shiftId, syncFlag = "N", localVersion = current.localVersion + 1, updatedBy = by, updatedAt = now))
            else -> dao.insert(
                AssignShiftEntity(
                    customerId = Tenant.CUSTOMER_ID, userId = userDbId, shiftId = shiftId, effectiveFrom = today,
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                )
            )
        }
    }

    private suspend fun requireUser(id: Long): UserEntity =
        userDao.getById(id)?.takeIf { it.customerId == Tenant.CUSTOMER_ID } ?: throw AuthException("User not found.")

    /** Uses the PWD_MIN_LENGTH that applies to [userId] (customer-wide when null). */
    private suspend fun checkPasswordLength(password: String, userId: Long?) {
        val min = settingsRepository.getSettings(userId).pwdMinLength
        if (password.length < min) throw AuthException("The password must have at least $min characters.")
    }

    private fun checkUserType(userType: String) {
        if (userType != UserTypes.ADMIN && userType != UserTypes.OPERATOR) throw AuthException("Choose Admin or User.")
    }

    /** U01, U02, … : one more than the highest code ever used. */
    private suspend fun nextUserCode(): String {
        val highest = userDao.getAllUserCodes(Tenant.CUSTOMER_ID)
            .mapNotNull { USER_CODE_PATTERN.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "U%02d".format(highest + 1)
    }

    private fun now(): String = DateTimes.toDbText(time.now())

    private fun UserEntity.touched(by: String, now: String) =
        copy(syncFlag = "N", localVersion = localVersion + 1, updatedBy = by, updatedAt = now)

    private fun UserEntity.toSummary() = UserSummary(
        id = id,
        userId = userId,
        userName = userName,
        userType = userType,
        userCode = userCode.orEmpty(),
        allowed = allowFlag == "Y",
        failedAttempts = failedAttempts,
        mustChangePassword = mustChangePwd == "Y",
        sessionStartedAt = sessionStartedAt.takeIf { deviceId != null },
    )

    private companion object {
        val USER_ID_PATTERN = Regex("^[a-z0-9._]{3,20}$")
        val USER_CODE_PATTERN = Regex("^U(\\d+)$")
    }
}
