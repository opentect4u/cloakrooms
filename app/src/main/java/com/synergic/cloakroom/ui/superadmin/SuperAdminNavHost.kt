package com.synergic.cloakroom.ui.superadmin

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.synergic.cloakroom.ui.reports.ReportsScreen
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.ui.masters.MastersScreen
import com.synergic.cloakroom.ui.password.ChangePasswordScreen
import com.synergic.cloakroom.ui.settings.SettingsScreen

private object SaRoutes {
    const val CUSTOMERS = "sa_customers"
    const val CUSTOMER_FORM = "sa_customer_form?id={id}"
    const val CUSTOMER = "sa_customer/{id}"
    const val CUSTOMER_SETTINGS = "sa_customer/{id}/settings"
    const val CUSTOMER_MASTERS = "sa_customer/{id}/masters"
    const val CUSTOMER_REPORTS = "sa_customer/{id}/reports"
    const val CHANGE_PASSWORD = "sa_change_password"

    fun form(id: Long? = null) = "sa_customer_form?id=${id ?: 0L}"
    fun customer(id: Long) = "sa_customer/$id"
    fun settings(id: Long) = "sa_customer/$id/settings"
    fun masters(id: Long) = "sa_customer/$id/masters"
    fun reports(id: Long) = "sa_customer/$id/reports"
}

/**
 * Super Admin menu (user type S, online only; architecture PDF "Super admin" phase 1): customers,
 * their user limit, registrations (renewal date), users, settings, masters and reports. Counter screens are
 * not reachable.
 */
@Composable
fun SuperAdminNavHost(session: UserSession) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = SaRoutes.CUSTOMERS) {
        composable(SaRoutes.CUSTOMERS) {
            CustomersScreen(
                session = session,
                onAdd = { nav.navigate(SaRoutes.form()) },
                onOpen = { nav.navigate(SaRoutes.customer(it)) },
                onChangePassword = { nav.navigate(SaRoutes.CHANGE_PASSWORD) },
            )
        }
        composable(
            SaRoutes.CUSTOMER_FORM,
            arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L }),
        ) {
            CustomerFormScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id ->
                    nav.navigate(SaRoutes.customer(id)) { popUpTo(SaRoutes.CUSTOMERS) }
                },
            )
        }
        composable(SaRoutes.CUSTOMER, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            CustomerDetailScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate(SaRoutes.form(id)) },
                onSettings = { id -> nav.navigate(SaRoutes.settings(id)) },
                onMasters = { id -> nav.navigate(SaRoutes.masters(id)) },
                onReports = { id -> nav.navigate(SaRoutes.reports(id)) },
            )
        }
        composable(SaRoutes.CUSTOMER_SETTINGS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            // Same screen as the admin's Settings, with this customer's values (route argument "id").
            SettingsScreen(onBack = { nav.popBackStack() }, title = "Customer settings")
        }
        composable(SaRoutes.CHANGE_PASSWORD) {
            ChangePasswordScreen(forced = false, onDone = { nav.popBackStack() })
        }
        composable(SaRoutes.CUSTOMER_MASTERS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            // Same screen as the admin's Masters, with this customer's data on the server.
            MastersScreen(onBack = { nav.popBackStack() }, title = "Customer masters")
        }
        composable(SaRoutes.CUSTOMER_REPORTS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            // Same screen as the admin's Reports, with this customer's data from the server.
            ReportsScreen(onBack = { nav.popBackStack() }, title = "Customer reports")
        }
    }
}

/** Text for the screen: the server's message, or the first field problem for validation errors. */
internal fun Throwable.screenMessage(): String = when {
    this is ServerException && code == ServerException.VALIDATION_ERROR && fieldErrors.isNotEmpty() ->
        "Please check: " + fieldErrors.entries.joinToString("; ") { "${it.key.replace('_', ' ')} – ${it.value}" }
    this is ServerException && code == ServerException.CONFLICT ->
        "Already exists" + (details["key"]?.let { " ($it)" } ?: "") + "."
    else -> message ?: "Something went wrong."
}
