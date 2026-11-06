package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.domain.auth.AuthException
import com.synergic.cloakroom.domain.auth.LoginResult
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.settings.SettingKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AuthRepositoryTest {

    private lateinit var env: TestEnv

    @Before
    fun setUp() {
        env = TestEnv()
    }

    @After
    fun tearDown() = env.close()

    private fun user(userId: String) = runBlocking { env.db.userDao().getByUserId(userId)!! }

    private fun failureMessage(userId: String, password: String): String = runBlocking {
        val result = env.auth.login(userId, password)
        check(result is LoginResult.Failure) { "Expected a failure, got $result" }
        result.message
    }

    private fun setSetting(name: String, value: String) = runBlocking {
        env.loginOk("admin", "Admin@123")
        env.settings.updateSettings(mapOf(name to value))
        env.auth.logout()
    }

    // ---- Login (server, API_GUIDE.md §3) ----------------------------------------------------

    @Test
    fun `login stores the session and tokens and mirrors the user locally`() = runBlocking {
        val session = env.loginOk("user1", "User@123")

        assertEquals("access-user1", env.store.accessToken)
        assertEquals("refresh-user1", env.store.refreshToken)
        assertEquals(session, env.store.session)
        assertEquals(SessionState.LoggedIn(session), env.auth.sessionState.value)
        assertFalse(session.isAdmin)
        assertFalse("the server has no first-login flag", session.mustChangePassword)
        assertEquals(Tenant.CUSTOMER_ID, session.customerId)

        val row = user("user1")
        assertEquals("device-A", row.deviceId)
        assertEquals("2026-11-02 06:30:00", row.sessionStartedAt)
        assertEquals("Y", row.syncFlag)
    }

    @Test
    fun `server refusals become clear messages`() {
        assertEquals("Wrong user ID or password.", failureMessage("user1", "wrong"))
        assertEquals("Wrong user ID or password.", failureMessage("nobody", "x"))

        env.authApi.nextError = ServerException(
            ServerException.SESSION_ACTIVE, "User is already logged in on another device", 409,
            details = mapOf("session_started_at" to "2026-11-02 06:30:00", "device_id" to "******H-01"),
        )
        assertEquals(
            "Already logged in on another device since 02-11-2026 12:00 PM. Logout there first, or ask an admin to force logout.",
            failureMessage("user1", "User@123"),
        )
        env.authApi.nextError = ServerException(ServerException.SUBSCRIPTION_EXPIRED, "Subscription expired", 403, details = mapOf("renewal_dt" to "2026-10-31"))
        assertEquals("The subscription ended on 2026-10-31. Please contact Synergic.", failureMessage("user1", "User@123"))
        env.authApi.nextError = ServerException(ServerException.NETWORK, "offline")
        assertEquals("Cannot reach the server. Logging in needs internet.", failureMessage("user1", "User@123"))

        assertNull("no session after a refusal", env.store.session)
        assertNull(env.store.accessToken)
    }

    @Test
    fun `empty fields are refused without calling the server`() {
        assertEquals("Enter your user ID and password.", failureMessage(" ", ""))
    }

    @Test
    fun `logout tells the server and clears session and tokens`() = runBlocking {
        env.loginOk("user1", "User@123")
        env.auth.logout()
        assertEquals(1, env.authApi.logoutCalls)
        assertNull(env.store.session)
        assertNull(env.store.accessToken)
        assertEquals(SessionState.LoggedOut(null), env.auth.sessionState.value)
        env.loginOk("user1", "User@123")
        Unit
    }

    @Test
    fun `a counter user can log out offline`() = runBlocking {
        env.loginOk("user1", "User@123")
        env.connectivity.isUp = false
        env.auth.logout()
        assertEquals("no server call while offline", 0, env.authApi.logoutCalls)
        assertEquals(SessionState.LoggedOut(null), env.auth.sessionState.value)
    }

    @Test
    fun `session survives an app restart, also offline`() = runBlocking {
        val session = env.loginOk("user1", "User@123")
        env.connectivity.isUp = false
        env.restartApp()
        env.auth.restoreSession()
        assertEquals(SessionState.LoggedIn(session), env.auth.sessionState.value)
    }

    @Test
    fun `restart without a saved session or tokens shows login`() = runBlocking {
        env.auth.restoreSession()
        assertEquals(SessionState.LoggedOut(null), env.auth.sessionState.value)

        env.loginOk("user1", "User@123")
        env.store.clearTokens()
        env.restartApp()
        env.auth.restoreSession()
        assertEquals(SessionState.LoggedOut(null), env.auth.sessionState.value)
    }

    @Test
    fun `session ended by the server goes back to login with the reason`() = runBlocking {
        env.loginOk("user1", "User@123")
        env.authEvents.sessionEnded("Your session was ended. Please log in again.")
        withTimeout(5_000) {
            env.auth.sessionState.first { it is SessionState.LoggedOut }
        }
        assertEquals(SessionState.LoggedOut("Your session was ended. Please log in again."), env.auth.sessionState.value)
        assertNull(env.store.accessToken)
    }

    @Test
    fun `app start with only a server customer on the phone does not seed demo data again`() = runBlocking {
        // As found on the test phone: no demo customer 1, a server customer 2 with users "admin" and "user1".
        val w = env.db.openHelper.writableDatabase
        w.execSQL("UPDATE md_customer SET customer_id = 2")
        w.execSQL("UPDATE md_user SET customer_id = 2")
        w.execSQL("UPDATE md_setting SET customer_id = 2")
        try {
            Tenant.CUSTOMER_ID = 1 // what a fresh app process starts with
            env.restartApp()
            env.auth.restoreSession() // used to crash: UNIQUE constraint failed: md_user.user_id
            assertEquals(1, env.db.customerDao().count())
            assertEquals(2, env.db.userDao().getAll(2).size)
        } finally {
            Tenant.CUSTOMER_ID = 1
        }
    }

    @Test
    fun `restoring a saved session switches to its customer before anything else`() = runBlocking {
        env.loginOk("user1", "User@123")
        val w = env.db.openHelper.writableDatabase
        w.execSQL("UPDATE md_customer SET customer_id = 2")
        w.execSQL("UPDATE md_user SET customer_id = 2")
        w.execSQL("UPDATE md_setting SET customer_id = 2")
        env.store.session = env.store.session!!.copy(customerId = 2)
        try {
            Tenant.CUSTOMER_ID = 1
            env.restartApp()
            env.auth.restoreSession()
            assertEquals(2L, Tenant.CUSTOMER_ID)
            assertTrue(env.auth.sessionState.value is SessionState.LoggedIn)
        } finally {
            Tenant.CUSTOMER_ID = 1
        }
    }

    @Test
    fun `super admin logs in without a customer and touches no local data`() = runBlocking {
        val usersBefore = env.db.userDao().getAll(Tenant.CUSTOMER_ID).size
        val session = env.loginOk("superadmin", "Super@123")
        assertTrue(session.isSuperAdmin)
        assertTrue(session.needsOnline)
        assertNull(session.customerId)
        assertEquals(usersBefore, env.db.userDao().getAll(Tenant.CUSTOMER_ID).size)
        assertNull(env.db.userDao().getByUserId("superadmin"))
    }

    @Test
    fun `admins and super admins need the network, counter users do not`() {
        assertTrue(env.loginOk("admin", "Admin@123").needsOnline)
        runBlocking { env.auth.logout() }
        assertFalse(env.loginOk("user1", "User@123").needsOnline)
    }

    // ---- Password change and app version (API_GUIDE.md §3 items 7–8) ---------------------------

    @Test
    fun `a user an admin created must change the password first`() = runBlocking {
        env.authApi.mustChangePwd = "Y"
        val session = env.loginOk("user1", "User@123")
        assertTrue(session.mustChangePassword)
        assertTrue("kept across a restart", env.store.session!!.mustChangePassword)

        env.auth.changePassword("User@123", "NewPass@2026")
        assertEquals("NewPass@2026", env.authApi.changedTo)
        val after = (env.auth.sessionState.value as SessionState.LoggedIn).session
        assertFalse(after.mustChangePassword)
        assertEquals("same tokens, no new login", "access-user1", env.store.accessToken)
    }

    @Test
    fun `change password checks the new password and explains a wrong current one`() {
        env.loginOk("user1", "User@123")
        fun message(current: String, new: String) =
            assertThrows(AuthException::class.java) { runBlocking { env.auth.changePassword(current, new) } }.message!!
        assertTrue(message("User@123", "User@123").contains("different"))
        assertTrue(message("User@123", "short1").contains("8 to 128"))
        assertTrue(message("User@123", "lettersonly").contains("8 to 128"))

        env.authApi.changePasswordError = ServerException(
            ServerException.VALIDATION_ERROR, "Validation failed", 400, fieldErrors = mapOf("current_password" to "is wrong"),
        )
        assertEquals("Current password is wrong.", message("Bad@1234", "NewPass@2026"))
        assertTrue("a wrong current password does not log out", env.auth.sessionState.value is SessionState.LoggedIn)
    }

    @Test
    fun `PASSWORD_CHANGE_REQUIRED from the server opens the change-password screen`() = runBlocking {
        env.loginOk("user1", "User@123")
        env.authEvents.passwordChangeRequired()
        val state = withTimeout(5_000) {
            env.auth.sessionState.first { (it as? SessionState.LoggedIn)?.session?.mustChangePassword == true }
        }
        assertTrue(state is SessionState.LoggedIn)
    }

    @Test
    fun `login sends the version code and a mandatory update blocks it`() {
        env.loginOk("user1", "User@123")
        assertEquals(com.synergic.cloakroom.BuildConfig.VERSION_CODE, env.authApi.lastVersionCode)
        runBlocking { env.auth.logout() }

        env.authApi.nextError = ServerException(
            ServerException.UPDATE_REQUIRED, "This app version is no longer supported; update", 426,
            details = mapOf("latest" to """{"version_code":10,"version_name":"1.0.0","apk_url":"/downloads/cloakroom-1.0.0.apk","release_notes":"First release"}"""),
        )
        val r = runBlocking { env.auth.login("user1", "User@123") }
        assertEquals(
            LoginResult.UpdateRequired("1.0.0", "http://test.invalid:4000/downloads/cloakroom-1.0.0.apk", "First release"),
            r,
        )
        assertNull(env.store.session)
    }

    // ---- Permissions -----------------------------------------------------------------------

    @Test
    fun `normal user cannot use any admin action`() {
        env.loginOk("user1", "User@123")
        val adminId = env.userDbId("admin")
        listOf<suspend () -> Unit>(
            { env.auth.addUser("user2", "Two", "O", "Password1") },
            { env.auth.updateUser(adminId, "Hacked", "O") },
            { env.auth.setBlocked(adminId, true) },
            { env.auth.resetPassword(adminId, "Password1") },
            { env.auth.forceLogout(adminId) },
            { env.auth.observeUsers().first() },
        ).forEach { action ->
            assertThrows(NotAuthorizedException::class.java) { runBlocking { action() } }
        }
        val admin = user("admin")
        assertEquals("Admin", admin.userName)
        assertEquals("Y", admin.allowFlag)
    }

    @Test
    fun `admin actions need a login`() {
        assertThrows(NotAuthorizedException::class.java) { runBlocking { env.auth.forceLogout(1) } }
    }

    // ---- User management -------------------------------------------------------------------

    @Test
    fun `admin adds a user with the next user code`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        env.auth.addUser("Counter2", "Counter Two", "O", "First@123")
        val added = user("counter2")
        assertEquals("U03", added.userCode)
        assertEquals("Y", added.mustChangePwd)
        assertEquals("admin", added.createdBy)
        assertTrue(env.hasher.verify("First@123", added.passwordHash))
        assertEquals(3, env.auth.observeUsers().first().size)

        assertThrows(AuthException::class.java) { runBlocking { env.auth.addUser("counter2", "Again", "O", "First@123") } }
        assertThrows(AuthException::class.java) { runBlocking { env.auth.addUser("x", "Short id", "O", "First@123") } }
        assertThrows(AuthException::class.java) { runBlocking { env.auth.addUser("counter3", "Bad type", "S", "First@123") } }
        Unit
    }

    @Test
    fun `admin blocks, unblocks and resets a password`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        val id = env.userDbId("user1")
        env.auth.setBlocked(id, true)
        assertEquals("N", user("user1").allowFlag)
        env.auth.setBlocked(id, false)
        assertEquals("Y", user("user1").allowFlag)
        assertEquals(0, user("user1").failedAttempts)

        env.auth.resetPassword(id, "Reset@123")
        assertTrue(env.hasher.verify("Reset@123", user("user1").passwordHash))
        assertEquals("Y", user("user1").mustChangePwd)
    }

    @Test
    fun `admin cannot block, force out or demote themselves`() {
        env.loginOk("admin", "Admin@123")
        val self = env.userDbId("admin")
        assertThrows(AuthException::class.java) { runBlocking { env.auth.setBlocked(self, true) } }
        assertThrows(AuthException::class.java) { runBlocking { env.auth.forceLogout(self) } }
        assertThrows(AuthException::class.java) { runBlocking { env.auth.updateUser(self, "Admin", "O") } }
    }

    @Test
    fun `users list shows who is logged in and since when`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        val users = env.auth.observeUsers().first().associateBy { it.userId }
        assertEquals("2026-11-02 06:30:00", users.getValue("admin").sessionStartedAt)
        assertFalse(users.getValue("user1").isLoggedIn)
    }

    // ---- Login shift (td_assign_shift) -----------------------------------------------------

    private fun shiftId(name: String) = runBlocking { env.db.shiftDao().getActive(Tenant.CUSTOMER_ID).first { it.shiftName == name }.shiftId }

    /** As admin (at 12:00 IST on 2 Nov), gives user1 a login shift, then logs out. */
    private fun assignUser1(shift: String?) = runBlocking {
        env.clock.instant = Instant.parse("2026-11-02T06:30:00Z")
        env.loginOk("admin", "Admin@123")
        env.auth.updateUser(env.userDbId("user1"), "Counter User", "O", shift?.let { shiftId(it) })
        env.auth.logout()
    }

    /** Logs user1 in at [utc]; logs out again after a success. */
    private fun loginUser1At(utc: String): LoginResult = runBlocking {
        env.clock.instant = Instant.parse(utc)
        val r = env.auth.login("user1", "User@123")
        if (r is LoginResult.Success) env.auth.logout()
        r
    }

    private fun assertRefused(r: LoginResult, shiftLabel: String) =
        assertEquals(LoginResult.Failure("You can log in only during your shift $shiftLabel."), r)

    @Test
    fun `counter user with a shift logs in only during it`() {
        assignUser1("Morning")
        assertTrue(loginUser1At("2026-11-02T06:30:00Z") is LoginResult.Success) // 12:00 IST
        assertRefused(loginUser1At("2026-11-02T14:30:00Z"), "Morning (06:00–14:00)") // 20:00 IST
        assertNull("a refused login leaves no session", env.store.session)
        assertNull(env.store.accessToken)
    }

    @Test
    fun `login opens SHIFT_EARLY_LOGIN_MIN before the shift`() {
        assignUser1("Morning")
        assertTrue(loginUser1At("2026-11-02T00:20:00Z") is LoginResult.Success) // 05:50 IST
        assertRefused(loginUser1At("2026-11-02T00:10:00Z"), "Morning (06:00–14:00)") // 05:40 IST
    }

    @Test
    fun `night shift crosses midnight`() {
        assignUser1("Night")
        assertTrue(loginUser1At("2026-11-02T18:00:00Z") is LoginResult.Success) // 23:30 IST
        assertTrue(loginUser1At("2026-11-02T20:30:00Z") is LoginResult.Success) // 02:00 IST on 3 Nov
        assertRefused(loginUser1At("2026-11-03T01:30:00Z"), "Night (22:00–06:00)") // 07:00 IST
    }

    @Test
    fun `no limit when SHIFT_LOGIN_FLAG is off`() {
        assignUser1("Morning")
        setSetting(SettingKeys.SHIFT_LOGIN_FLAG, "N")
        assertTrue(loginUser1At("2026-11-02T14:30:00Z") is LoginResult.Success)
    }

    @Test
    fun `any time removes the limit and admins are never limited`() = runBlocking {
        assignUser1("Morning")
        assignUser1(null)
        assertTrue(loginUser1At("2026-11-02T14:30:00Z") is LoginResult.Success)
        assertTrue(env.db.assignShiftDao().getActive(Tenant.CUSTOMER_ID, env.userDbId("user1")).isEmpty())

        env.loginOk("admin", "Admin@123")
        env.auth.updateUser(env.userDbId("admin"), "Admin", "A", shiftId("Morning"))
        assertTrue("admins get no login shift", env.db.assignShiftDao().getActive(Tenant.CUSTOMER_ID, env.userDbId("admin")).isEmpty())
        env.auth.logout()
        env.clock.instant = Instant.parse("2026-11-02T14:30:00Z")
        env.loginOk("admin", "Admin@123")
        Unit
    }

    @Test
    fun `a removed shift no longer limits login`() = runBlocking {
        assignUser1("Morning")
        env.loginOk("admin", "Admin@123")
        MasterRepositoryImpl(env.db, env.sessionManager, env.clock).deleteShift(shiftId("Morning"))
        env.auth.logout()
        assertTrue(loginUser1At("2026-11-02T14:30:00Z") is LoginResult.Success)
    }

    @Test
    fun `shift changes keep their history in td_assign_shift`() = runBlocking {
        assignUser1("Morning")
        assignUser1("Evening") // same day: the row is changed, not added
        val user1 = env.userDbId("user1")
        assertEquals(listOf(shiftId("Evening")), env.db.assignShiftDao().getActive(Tenant.CUSTOMER_ID, user1).map { it.shiftId })

        // Next day: a new row; the old one stays as history.
        env.clock.instant = Instant.parse("2026-11-03T06:30:00Z")
        env.loginOk("admin", "Admin@123")
        env.auth.updateUser(user1, "Counter User", "O", shiftId("Night"))
        val rows = env.db.assignShiftDao().getActive(Tenant.CUSTOMER_ID, user1).sortedBy { it.effectiveFrom }
        assertEquals(listOf("2026-11-02", "2026-11-03"), rows.map { it.effectiveFrom })
        assertEquals("N", rows.last().syncFlag)
        assertEquals("admin", rows.last().createdBy)

        val summary = env.auth.observeUsers().first().single { it.userId == "user1" }
        assertEquals(shiftId("Night"), summary.shiftId)
        assertEquals("Night (22:00–06:00)", summary.shiftLabel)
    }

    @Test
    fun `new user gets a login shift and a bad shift is refused`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        assertEquals(listOf("Morning", "Evening", "Night"), env.auth.shiftOptions().map { it.name })
        env.auth.addUser("counter2", "Counter Two", "O", "First@123", shiftId("Evening"))
        val summary = env.auth.observeUsers().first().single { it.userId == "counter2" }
        assertEquals("Evening (14:00–22:00)", summary.shiftLabel)
        assertEquals(
            "Choose a shift from the list.",
            assertThrows(AuthException::class.java) {
                runBlocking { env.auth.updateUser(env.userDbId("counter2"), "Counter Two", "O", 999) }
            }.message,
        )
        Unit
    }
}
