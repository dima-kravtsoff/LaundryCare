package com.laundrycare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.laundrycare.ui.AddEditScreen
import com.laundrycare.ui.ClosetScreen
import com.laundrycare.ui.ClosetViewModel
import com.laundrycare.ui.DetailScreen
import com.laundrycare.ui.LaundryCareTheme
import com.laundrycare.ui.MatchScreen
import com.laundrycare.ui.PickScreen
import com.laundrycare.ui.PlanLoadScreen
import com.laundrycare.ui.SettingsScreen
import com.laundrycare.ui.TipsScreen

object Routes {
    const val CLOSET = "closet"
    const val ADD = "add"
    const val DETAIL = "detail/{id}"
    const val MATCH = "match"
    const val PLAN = "plan"
    const val SETTINGS = "settings"
    const val TIPS = "tips"
    const val PICK = "pick/{mode}/{id}"

    fun detail(id: String) = "detail/$id"
    fun pick(mode: String, id: String) = "pick/$mode/$id"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LaundryCareTheme { App() }
        }
    }
}

@Composable
fun App() {
    val vm: ClosetViewModel = viewModel()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    // Each screen has its own app bar that handles the status bar, so the outer
    // scaffold must not pad for it as well.
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.CLOSET, modifier = Modifier.fillMaxSize().padding(padding)) {
            composable(Routes.CLOSET) {
                ClosetScreen(
                    vm = vm,
                    onOpen = { nav.navigate(Routes.detail(it)) },
                    onAdd = { vm.startAdd(); nav.navigate(Routes.ADD) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onTips = { nav.navigate(Routes.TIPS) },
                    onPlan = { nav.navigate(Routes.PLAN) },
                    onSnap = { uri -> vm.snapToFind(uri, forPlan = false); nav.navigate(Routes.MATCH) },
                    onResumeDraft = { nav.navigate(Routes.ADD) },
                )
            }
            composable(Routes.ADD) {
                AddEditScreen(
                    vm = vm,
                    onSaved = { id, wasEditing ->
                        if (wasEditing) nav.popBackStack()
                        else nav.navigate(Routes.detail(id)) { popUpTo(Routes.CLOSET) }
                    },
                    onCancel = { nav.popBackStack() },
                    onCopyFrom = { nav.navigate(Routes.pick("copy", "-")) },
                )
            }
            composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                DetailScreen(
                    vm = vm,
                    id = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { g -> vm.startEdit(g); nav.navigate(Routes.ADD) },
                    onReread = { g -> vm.rereadTag(g); nav.navigate(Routes.ADD) },
                    onApplyTo = { nav.navigate(Routes.pick("apply", id)) },
                    onGone = { nav.popBackStack(Routes.CLOSET, inclusive = false) },
                )
            }
            composable(Routes.MATCH) {
                MatchScreen(
                    vm = vm,
                    onOpen = { nav.navigate(Routes.detail(it)) },
                    onBack = { nav.popBackStack() },
                    onAddNew = { vm.addQueryAsNewGarment(); nav.navigate(Routes.ADD) { popUpTo(Routes.CLOSET) } },
                    onPickedForPlan = { nav.popBackStack() },
                )
            }
            composable(Routes.PLAN) {
                PlanLoadScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpen = { nav.navigate(Routes.detail(it)) },
                    onSnap = { uri -> vm.snapToFind(uri, forPlan = true); nav.navigate(Routes.MATCH) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable(Routes.TIPS) {
                TipsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                Routes.PICK,
                arguments = listOf(navArgument("mode") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                PickScreen(
                    vm = vm,
                    mode = entry.arguments?.getString("mode").orEmpty(),
                    sourceId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
