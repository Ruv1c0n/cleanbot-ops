package com.cleanbot.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cleanbot.app.ui.detail.RobotDetailScreen
import com.cleanbot.app.ui.fleet.FleetListScreen

@Composable
fun CleanBotNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "fleet") {
        composable("fleet") {
            FleetListScreen(onRobotClick = { id -> navController.navigate("robot/$id") })
        }
        composable("robot/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: return@composable
            RobotDetailScreen(robotId = id, onBack = { navController.popBackStack() })
        }
    }
}
