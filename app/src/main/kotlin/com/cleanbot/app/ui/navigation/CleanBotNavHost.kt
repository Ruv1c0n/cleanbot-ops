package com.cleanbot.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cleanbot.app.di.AppContainer
import com.cleanbot.app.ui.detail.RobotDetailScreen
import com.cleanbot.app.ui.editor.PlanEditorScreen
import com.cleanbot.app.ui.fleet.FleetListScreen
import com.cleanbot.app.ui.onboarding.OnboardingScreen
import com.cleanbot.app.ui.settings.SettingsScreen

@Composable
fun CleanBotNavHost(
    navController: NavHostController = rememberNavController(),
    /** Id робота из intent-экстры тапа по уведомлению — открыть его дашборд сразу. */
    initialRobotId: String? = null
) {
    LaunchedEffect(initialRobotId) {
        initialRobotId?.takeIf { it.isNotBlank() }?.let { navController.navigate("robot/$it") }
    }
    // Онбординг — стартовый экран только до первого завершения (AppContainer.init
    // уже прочитал флаг в CleanBotApp.onCreate, до первой композиции).
    // remember: замораживаем значение на жизнь composition — пересчёт при
    // рекомпозиции сменил бы ключ startDestination, навигация пересобрала бы
    // граф и вытолкнула весь бэкстек посреди сессии (navigation-compose 2.7.7).
    val startDestination = remember { if (AppContainer.onboardingDone) "fleet" else "onboarding" }
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("onboarding") {
            OnboardingScreen(onDone = {
                AppContainer.markOnboardingDone()
                navController.navigate("fleet") {
                    popUpTo("onboarding") { inclusive = true }
                }
            })
        }
        composable("fleet") {
            FleetListScreen(
                onRobotClick = { id -> navController.navigate("robot/$id") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("robot/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: return@composable
            RobotDetailScreen(
                robotId = id,
                onBack = { navController.popBackStack() },
                onEditPlan = { navController.navigate("robot/$id/edit") }
            )
        }
        composable("robot/{id}/edit") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: return@composable
            PlanEditorScreen(robotId = id, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
