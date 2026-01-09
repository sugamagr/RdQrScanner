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
import com.qrscanner.app.ui.screens.AppInfoScreen
import com.qrscanner.app.ui.screens.HomeScreen
import com.qrscanner.app.ui.screens.HowItWorksScreen
import com.qrscanner.app.ui.screens.RDGeneratorScreen
import com.qrscanner.app.ui.screens.RDScannerScreen
import com.qrscanner.app.ui.screens.SessionDetailScreen
import com.qrscanner.app.ui.screens.SessionHistoryScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object RDScanner : Screen("rd_scanner")
    data object SessionHistory : Screen("session_history")
    data object SessionDetail : Screen("session_detail/{sessionId}") {
        fun createRoute(sessionId: Long) = "session_detail/$sessionId"
    }
    data object RDGenerator : Screen("rd_generator")
    data object HowItWorks : Screen("how_it_works")
    data object AppInfo : Screen("app_info")
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
                onNavigateToGenerator = { navController.navigate(Screen.RDGenerator.route) },
                onNavigateToHowItWorks = { navController.navigate(Screen.HowItWorks.route) },
                onNavigateToAppInfo = { navController.navigate(Screen.AppInfo.route) }
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
        
        composable(Screen.RDGenerator.route) {
            RDGeneratorScreen(
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
    }
}
