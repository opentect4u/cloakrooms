package com.synergic.cloakroom.data

import androidx.test.core.app.ApplicationProvider
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.numbering.UserCounterSequence
import com.synergic.cloakroom.data.repository.AuthRepositoryImpl
import com.synergic.cloakroom.data.repository.BillRepositoryImpl
import com.synergic.cloakroom.data.repository.SubscriptionRepositoryImpl
import com.synergic.cloakroom.data.repository.LuggageRepositoryImpl
import com.synergic.cloakroom.data.repository.TariffLookup
import com.synergic.cloakroom.data.repository.MasterRepositoryImpl
import com.synergic.cloakroom.data.repository.ReportRepositoryImpl
import com.synergic.cloakroom.data.repository.SettingsRepositoryImpl
import com.synergic.cloakroom.data.security.PasswordHasher
import com.synergic.cloakroom.data.seed.DemoSeeder
import com.synergic.cloakroom.data.session.SessionManager
import com.synergic.cloakroom.data.sync.ServerDataImporter
import com.synergic.cloakroom.data.sync.SyncCursorStore
import com.synergic.cloakroom.data.sync.SyncManager
import com.synergic.cloakroom.data.remote.PullResponseDto
import com.synergic.cloakroom.data.remote.PushRequestDto
import com.synergic.cloakroom.data.remote.PushResponseDto
import com.synergic.cloakroom.data.remote.PushResultDto
import com.synergic.cloakroom.data.remote.SyncApi
import com.synergic.cloakroom.data.remote.ApiConfig
import com.synergic.cloakroom.data.remote.AuthApi
import com.synergic.cloakroom.data.remote.AuthEvents
import com.synergic.cloakroom.data.remote.ChangePasswordRequestDto
import com.synergic.cloakroom.data.remote.CountersDto
import com.synergic.cloakroom.data.remote.CustomerDto
import com.synergic.cloakroom.data.remote.LoginDataDto
import com.synergic.cloakroom.data.remote.MastersDto
import com.synergic.cloakroom.data.remote.LoginRequestDto
import com.synergic.cloakroom.data.remote.MeDto
import com.synergic.cloakroom.data.remote.UserDto
import com.synergic.cloakroom.data.session.SessionStore
import com.synergic.cloakroom.data.session.TokenStore
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.remote.Connectivity
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.time.DateTimes
import kotlinx.coroutines.flow.MutableStateFlow
import com.synergic.cloakroom.domain.auth.LoginResult
import com.synergic.cloakroom.domain.device.DeviceInfo
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.time.Instant

/** In-memory session store; survives a simulated app restart when passed to a new [TestEnv.newAuth]. */
class FakeSessionStore : SessionStore, TokenStore {
    var session: UserSession? = null
    override fun savedSession(): UserSession? = session
    override fun save(session: UserSession) { this.session = session }
    override fun clear() { session = null; clearTokens() }

    override var accessToken: String? = null
    override var refreshToken: String? = null
    override fun saveTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }
    override fun clearTokens() { accessToken = null; refreshToken = null }
}

class FakeConnectivity(var isUp: Boolean = true) : Connectivity {
    override val online = MutableStateFlow(isUp)
    override fun isOnline(): Boolean = isUp
}

/**
 * Plays the server for [AuthApi.login]: accepts the seeded users with their seeded passwords (it
 * checks the hash kept in the local md_user row) and answers like API_GUIDE.md §3.
 */
class FakeAuthApi(private val env: TestEnv) : AuthApi {
    var logoutCalls = 0
    /** Thrown by the next login instead of logging in. */
    var nextError: ServerException? = null
    /** must_change_pwd sent with the next logins (an admin created / reset the user). */
    var mustChangePwd = "N"
    /** The last version_code a login sent. */
    var lastVersionCode: Int? = null
    /** Thrown by change-password instead of changing. */
    var changePasswordError: ServerException? = null
    var changedTo: String? = null
    /** Sent with the next logins of customer users (masters, §8 settings, counters). */
    var masters: MastersDto? = null
    var serverSettings: Map<String, String>? = null
    var counters: CountersDto? = null
    /** A super admin the fake server knows (no local row, no customer). */
    val superAdmin = UserDto(id = 900, customerId = null, userId = "superadmin", userName = "Synergic", userType = "S")
    val superAdminPassword = "Super@123"

    override suspend fun login(request: LoginRequestDto): LoginDataDto {
        lastVersionCode = request.versionCode
        nextError?.let { nextError = null; throw it }
        val now = DateTimes.toDbText(env.clock.now())
        if (request.userId == superAdmin.userId && request.password == superAdminPassword) {
            return LoginDataDto("access-s", "refresh-s", superAdmin, serverTime = now)
        }
        val u = env.db.userDao().getByUserId(request.userId.lowercase())
        if (u == null || u.passwordHash == null || !env.hasher.verify(request.password, u.passwordHash)) {
            throw ServerException(ServerException.UNAUTHORIZED, "Invalid user id or password", 401)
        }
        if (u.allowFlag != "Y") throw ServerException(ServerException.FORBIDDEN, "User is blocked", 403)
        val c = env.db.customerDao().get(u.customerId!!)!!
        return LoginDataDto(
            accessToken = "access-${u.userId}",
            refreshToken = "refresh-${u.userId}",
            user = UserDto(u.id, u.customerId, u.userId, u.userName, u.userCode, u.userType, u.allowFlag, mustChangePwd = mustChangePwd),
            customer = CustomerDto(c.customerId, c.customerName, c.customerAddr, null, c.pinCode, c.mobileNo, c.email, c.gstNo, c.upiId, c.renewalDt, c.userLimit),
            masters = masters,
            settings = serverSettings,
            counters = counters,
            serverTime = now,
        )
    }

    override suspend fun me(): MeDto = MeDto(superAdmin)

    override suspend fun logout() { logoutCalls++ }

    override suspend fun changePassword(request: ChangePasswordRequestDto): String {
        changePasswordError?.let { throw it }
        changedTo = request.newPassword
        return "N"
    }
}

/** Plays the server for push / pull: accepts every row; [pullPages] are answered in order. */
class FakeSyncApi : SyncApi {
    val pushes = mutableListOf<PushRequestDto>()
    /** Thrown by the next push instead of answering (e.g. no network). */
    var pushError: ServerException? = null
    /** Rows answered EXCEPTION instead of ACCEPTED (trans_id). */
    val exceptions = mutableSetOf<String>()
    val pullPages = ArrayDeque<PullResponseDto>()
    val pullCalls = mutableListOf<Pair<String?, String?>>()
    var serverTime = "2026-11-02 07:00:00"

    override suspend fun push(request: PushRequestDto): PushResponseDto {
        pushError?.let { throw it }
        pushes += request
        fun status(t: String) = if (t in exceptions) "EXCEPTION" else "ACCEPTED"
        return PushResponseDto(
            results = request.luggage.map { PushResultDto("td_luggage_in_out", it.transId, it.localVersion, status(it.transId)) } +
                request.receipts.map { PushResultDto("td_receipt", it.transId, it.localVersion, status(it.transId)) },
            serverTime = serverTime,
        )
    }

    override suspend fun pull(since: String?, cursor: String?): PullResponseDto {
        pullCalls += since to cursor
        return pullPages.removeFirstOrNull() ?: PullResponseDto(serverTime = serverTime)
    }

    /** GET /customers/me; null = the call fails (e.g. no network). */
    var ownCustomer: CustomerDto? = null

    override suspend fun ownCustomer(): CustomerDto =
        ownCustomer ?: throw ServerException(ServerException.NETWORK, "No network")
}

class InMemoryCursorStore : SyncCursorStore {
    val values = mutableMapOf<String, String>()
    override fun since(key: String): String? = values[key]
    override fun setSince(key: String, serverTime: String?) { if (serverTime == null) values.remove(key) else values[key] = serverTime }
}

class MutableClock(var instant: Instant) : TimeSource {
    override fun now(): Instant = instant
}

/** Database, repositories and fakes wired together the way Hilt does it in the app. */
class TestEnv {
    val db: AppDatabase = AppDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    val hasher = PasswordHasher()
    val clock = MutableClock(Instant.parse("2026-11-02T06:30:00Z"))
    var deviceId = "device-A"
    val device = DeviceInfo { deviceId }
    val store = FakeSessionStore()
    val connectivity = FakeConnectivity()
    val authEvents = AuthEvents()
    val authApi = FakeAuthApi(this)
    val seeder = DemoSeeder(db, hasher, clock)
    val syncApi = FakeSyncApi()
    val cursors = InMemoryCursorStore()
    /** Plays the role of the app's @ApplicationScope; cancelled in [close]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var sessionManager = SessionManager()
        private set
    var sync = newSync()
        private set
    var settings = SettingsRepositoryImpl(db, sessionManager, hasher, clock, scope)
        private set
    var auth = newAuth()
        private set
    var reports = newReports()
        private set

    init {
        runBlocking { seeder.ensureSeeded() }
    }

    /** Built on demand, so it always uses the current session after [restartApp]. */
    val luggage get() = LuggageRepositoryImpl(db, sessionManager, settings, UserCounterSequence(db), device, clock, TariffLookup(db), subscription)

    val bills get() = BillRepositoryImpl(db, sessionManager, settings, UserCounterSequence(db), device, clock, TariffLookup(db), subscription)

    val subscription = SubscriptionRepositoryImpl(db, clock)

    val masters get() = MasterRepositoryImpl(db, sessionManager, clock)

    private fun newReports() = ReportRepositoryImpl(db, sessionManager, settings, hasher, clock)

    private fun newAuth() = AuthRepositoryImpl(
        db, settings, hasher, clock, device, store, store, sessionManager, seeder, authApi, connectivity, authEvents, scope,
        ApiConfig("http://test.invalid:4000"), ServerDataImporter(db), sync,
    )

    private fun newSync() = SyncManager(db, syncApi, ServerDataImporter(db), sessionManager, connectivity, cursors)

    /** Simulates closing and reopening the app: fresh in-memory state, same database and saved session. */
    fun restartApp() {
        sessionManager = SessionManager()
        sync = newSync()
        settings = SettingsRepositoryImpl(db, sessionManager, hasher, clock, scope)
        auth = newAuth()
        reports = newReports()
    }

    fun userDbId(userId: String): Long = runBlocking { db.userDao().getByUserId(userId)!!.id }

    /** Sets a customer-wide setting straight in the database (test set-up, no admin login). */
    fun setSettingDirect(name: String, value: String) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE md_setting SET setting_value = ? WHERE setting_name = ? AND user_id IS NULL", arrayOf(value, name),
        )
    }

    /** Changes a user's type straight in the database, e.g. to make old rows as a former counter user. */
    fun setUserType(userId: String, type: String) = runBlocking {
        val user = db.userDao().getByUserId(userId)!!
        db.openHelper.writableDatabase.execSQL("UPDATE md_user SET user_type = ? WHERE id = ?", arrayOf(type, user.id))
    }

    /** Logs in and asserts success. */
    fun loginOk(userId: String, password: String) = runBlocking {
        val result = auth.login(userId, password)
        check(result is LoginResult.Success) { "Login of $userId failed: $result" }
        result.session
    }

    fun close() {
        scope.cancel()
        db.close()
    }
}
