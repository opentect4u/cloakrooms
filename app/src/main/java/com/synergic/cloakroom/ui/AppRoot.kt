package com.synergic.cloakroom.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.remote.Connectivity
import com.synergic.cloakroom.domain.repository.AuthRepository
import com.synergic.cloakroom.ui.bags.BagsInStoreScreen
import com.synergic.cloakroom.ui.checkin.CheckinScreen
import com.synergic.cloakroom.ui.checkout.CheckoutScreen
import com.synergic.cloakroom.ui.checkout.CheckoutSearchScreen
import com.synergic.cloakroom.ui.checkout.CheckoutTarget
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.dashboard.DashboardScreen
import com.synergic.cloakroom.ui.login.LoginScreen
import com.synergic.cloakroom.ui.masters.MastersScreen
import com.synergic.cloakroom.ui.printer.PrinterSetupScreen
import com.synergic.cloakroom.ui.password.ChangePasswordScreen
import com.synergic.cloakroom.ui.print.DocumentKind
import com.synergic.cloakroom.ui.print.DocumentScreen
import com.synergic.cloakroom.ui.reports.ReportsScreen
import com.synergic.cloakroom.ui.settings.SettingsScreen
import com.synergic.cloakroom.ui.superadmin.SuperAdminNavHost
import com.synergic.cloakroom.ui.users.ServerUsersScreen
import com.synergic.cloakroom.ui.users.UsersScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    connectivity: Connectivity,
) : ViewModel() {
    val sessionState: StateFlow<SessionState> = authRepository.sessionState
    val online: StateFlow<Boolean> = connectivity.online

    fun logout() {
        viewModelScope.launch { runCatching { authRepository.logout() } }
    }

    init {
        viewModelScope.launch { authRepository.restoreSession() }
    }
}

private object Routes {
    const val HOME = "home"
    const val USERS = "users"
    const val SETTINGS = "settings"
    const val MASTERS = "masters"
    const val PRINTER_SETUP = "printer_setup"
    const val CHANGE_PASSWORD = "change_password"
    const val CHECKIN = "checkin"
    const val BAGS = "bags_in_store"
    const val REPORTS = "reports"
    const val CHECKOUT_SEARCH = "checkout_search"
    const val CHECKOUT = "checkout?transId={transId}&qr={qr}"
    const val DOCUMENT = "document/{kind}/{transId}?print={print}"

    fun checkout(target: CheckoutTarget) = when (target) {
        is CheckoutTarget.Local -> "checkout?transId=${Uri.encode(target.transId)}"
        is CheckoutTarget.FromQr -> "checkout?qr=${Uri.encode(target.qrText)}"
    }

    fun document(kind: DocumentKind, transId: String, print: Boolean) = "document/${kind.route}/$transId?print=$print"
}

/**
 * Chooses the screen from the session: Login when logged out, Change password on first login,
 * otherwise the main screens. Logging out from anywhere brings the user back to Login.
 */
@Composable
fun AppRoot(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    when (val s = state) {
        SessionState.Loading -> LoadingBox(Modifier.fillMaxSize())
        is SessionState.LoggedOut -> LoginScreen(message = s.message)
        is SessionState.LoggedIn -> {
            val online by viewModel.online.collectAsStateWithLifecycle()
            when {
                // Super admins and admins work only online; counter users may continue offline.
                s.session.needsOnline && !online -> OnlineRequiredScreen(s.session, onLogout = viewModel::logout)
                // First login or after an admin reset (must_change_pwd = Y): nothing else until changed.
                s.session.mustChangePassword -> ChangePasswordScreen(forced = true)
                s.session.isSuperAdmin -> SuperAdminNavHost(s.session)
                else -> MainNavHost(s.session)
            }
        }
    }
}

@Composable
private fun MainNavHost(session: UserSession) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            DashboardScreen(
                session = session,
                onCheckin = { nav.navigate(Routes.CHECKIN) },
                onCheckout = { nav.navigate(Routes.CHECKOUT_SEARCH) },
                onBagsInStore = { nav.navigate(Routes.BAGS) },
                onReports = { nav.navigate(Routes.REPORTS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onUsers = { nav.navigate(Routes.USERS) },
                onMasters = { nav.navigate(Routes.MASTERS) },
                onPrinterSetup = { nav.navigate(Routes.PRINTER_SETUP) },
                onChangePassword = { nav.navigate(Routes.CHANGE_PASSWORD) },
            )
        }
        composable(Routes.BAGS) {
            BagsInStoreScreen(
                onBack = { nav.popBackStack() },
                onCheckout = { transId -> nav.navigate(Routes.checkout(CheckoutTarget.Local(transId))) },
                onReprint = { transId -> nav.navigate(Routes.document(DocumentKind.RECEIPT, transId, print = false)) },
            )
        }
        // Normal users reach this only when REPORT_FLAG = Y; the screen and repository check again.
        composable(Routes.REPORTS) {
            ReportsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CHECKIN) {
            CheckinScreen(
                onBack = { nav.popBackStack() },
                onSaved = { transId ->
                    nav.navigate(Routes.document(DocumentKind.RECEIPT, transId, print = true)) {
                        popUpTo(Routes.CHECKIN) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.DOCUMENT,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("transId") { type = NavType.StringType },
                navArgument("print") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val isBill = entry.arguments?.getString("kind") == DocumentKind.BILL.route
            DocumentScreen(
                nextLabel = if (isBill) "Next check-out" else "New check-in",
                onNext = {
                    nav.navigate(if (isBill) Routes.CHECKOUT_SEARCH else Routes.CHECKIN) { popUpTo(Routes.HOME) }
                },
                onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.CHECKOUT_SEARCH) {
            CheckoutSearchScreen(
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.checkout(it)) },
            )
        }
        composable(
            Routes.CHECKOUT,
            arguments = listOf(
                navArgument("transId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("qr") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            CheckoutScreen(
                onBack = { nav.popBackStack() },
                onSaved = { transId, print ->
                    nav.navigate(Routes.document(DocumentKind.BILL, transId, print)) {
                        popUpTo(Routes.CHECKOUT_SEARCH) { inclusive = true }
                    }
                },
                onViewBill = { transId -> nav.navigate(Routes.document(DocumentKind.BILL, transId, print = false)) },
            )
        }
        // Every user connects the counter's printer; normal users save it for their own login.
        composable(Routes.PRINTER_SETUP) {
            PrinterSetupScreen(onBack = { nav.popBackStack() })
        }
        // Normal users see only the Print tab there, saved for their own login.
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        // Admin-only routes are not registered for normal users, so they cannot be opened at all.
        if (session.isAdmin) {
            composable(Routes.USERS) {
                // Logged in to the server: users live there. (Local demo data: this phone's users.)
                if (session.isAdmin && session.customerId != null) {
                    ServerUsersScreen(currentUserId = session.id, onBack = { nav.popBackStack() })
                } else {
                    UsersScreen(currentUserId = session.id, onBack = { nav.popBackStack() })
                }
            }
            composable(Routes.MASTERS) {
                MastersScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
