package com.qrscanner.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.ui.screens.AccountsScreen
import com.qrscanner.app.ui.screens.AddAccountsScreen
import com.qrscanner.app.ui.screens.AppInfoScreen
import com.qrscanner.app.ui.screens.HomeScreen
import com.qrscanner.app.ui.screens.HowItWorksScreen
import com.qrscanner.app.ui.screens.RDScannerScreen
import com.qrscanner.app.ui.screens.SessionDetailScreen
import com.qrscanner.app.ui.screens.SessionHistoryScreen
import com.qrscanner.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object RDScanner : Screen("rd_scanner")
    data object SessionHistory : Screen("session_history")
    data object SessionDetail : Screen("session_detail/{sessionId}") {
        fun createRoute(sessionId: Long) = "session_detail/$sessionId"
    }
    data object AddAccounts : Screen("add_accounts")
    data object Accounts : Screen("accounts")
    data object HowItWorks : Screen("how_it_works")
    data object AppInfo : Screen("app_info")
    data object Settings : Screen("settings")
}

@Composable
fun QRScannerNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = {
            fadeIn(animationSpec = tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + 
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                initialOffset = { it / 4 }
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300)) + 
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(350),
                targetOffset = { it / 4 }
            )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + 
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                initialOffset = { it / 4 }
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) + 
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(350),
                targetOffset = { it / 4 }
            )
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToScanner = { navController.navigate(Screen.RDScanner.route) },
                onNavigateToHistory = { navController.navigate(Screen.SessionHistory.route) },
                onNavigateToAddAccounts = { navController.navigate(Screen.AddAccounts.route) },
                onNavigateToAccounts = { navController.navigate(Screen.Accounts.route) },
                onNavigateToHowItWorks = { navController.navigate(Screen.HowItWorks.route) },
                onNavigateToAppInfo = { navController.navigate(Screen.AppInfo.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        
        composable(Screen.RDScanner.route) {
            RDScannerScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { sessionId ->
                    // Pop scanner from backstack and navigate to session detail
                    navController.popBackStack()
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
                }
            )
        }
        
        composable(Screen.SessionHistory.route) {
            SessionHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSession = { sessionId ->
                    navController.navigate(Screen.SessionDetail.createRoute(sessionId))
                }
            )
        }
        
        composable(
            route = Screen.SessionDetail.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            SessionDetailScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.AddAccounts.route) {
            AddAccountsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAccounts = {
                    navController.popBackStack()
                    navController.navigate(Screen.Accounts.route)
                }
            )
        }

        composable(Screen.Accounts.route) {
            AccountsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.HowItWorks.route) {
            HowItWorksScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.AppInfo.route) {
            AppInfoScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsRoute(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Wraps [SettingsScreen] with the sign-out sequence and device-settings
 * read. Hosted here (not in SettingsScreen.kt) so the screen file stays
 * pure UI and the orchestration lives next to the navigation graph that
 * needs it.
 *
 * Sign-out order is load-bearing: workers MUST be cancelled before the
 * cloud token is invalidated (else an in-flight push fires with a stale
 * token), and the local data wipe MUST happen before [clearOwner] so an
 * authoritative crash mid-wipe still leaves the user signed-in and able
 * to retry. After [clearOwner] returns, the device_settings observer in
 * AuthAwareRoot re-evaluates and auto-routes to SignInScreen.
 */
@Composable
private fun SettingsRoute(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()

    val deviceSettings by app.database.deviceSettingsDao().observe()
        .collectAsStateWithLifecycle(initialValue = null)

    var showConfirmDialog by remember { mutableStateOf(false) }
    var isSigningOut by remember { mutableStateOf(false) }
    var navigatedAway by remember { mutableStateOf(false) }

    // When clearOwner() lands, AuthAwareRoot snaps to SignInScreen and
    // this composable is torn down. The popBackStack guard prevents a
    // visible "Settings empty state" flash if the device_settings flow
    // emits the cleared row before the auth state catches up.
    LaunchedEffect(deviceSettings) {
        if (isSigningOut && !navigatedAway && deviceSettings?.ownerId.isNullOrBlank()) {
            navigatedAway = true
            onNavigateBack()
        }
    }

    SettingsScreen(
        signedInEmail = deviceSettings?.ownerId.orEmpty(),
        deviceName = deviceSettings?.deviceName.orEmpty(),
        operatorName = deviceSettings?.operatorName.orEmpty(),
        onBack = onNavigateBack,
        onSwitchOperator = { /* Wave 4 — operator switch flow is a separate spec section. */ },
        onSignOut = { showConfirmDialog = true },
        onOpenDiagnostics = { /* Phase 5 deliverable per SettingsScreen KDoc. */ }
    )

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSigningOut) showConfirmDialog = false },
            title = { Text("Sign out of this device?") },
            text = {
                Text(
                    "All local sessions, LOTs, RD numbers, and saved accounts " +
                        "will be deleted from this device. Cloud data is not " +
                        "affected; you can sign back in to restore everything."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isSigningOut,
                    onClick = {
                        isSigningOut = true
                        scope.launch {
                            runCatching { app.syncScheduler.cancelAll() }
                            runCatching { app.cloudClient.signOut() }
                            runCatching { app.database.wipeAllUserData() }
                            runCatching { app.database.deviceSettingsDao().clearOwner() }
                            showConfirmDialog = false
                        }
                    }
                ) {
                    Text(if (isSigningOut) "Signing out…" else "Sign out")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSigningOut,
                    onClick = { showConfirmDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
