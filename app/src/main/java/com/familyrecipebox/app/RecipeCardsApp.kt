package com.familyrecipebox.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.familyrecipebox.app.data.DEFAULT_RECIPE_CATEGORY
import com.familyrecipebox.app.ui.edit.EditScreen
import com.familyrecipebox.app.ui.edit.EditViewModel
import com.familyrecipebox.app.ui.home.HomeScreen
import com.familyrecipebox.app.ui.home.HomeViewModel
import com.familyrecipebox.app.ui.settings.SettingsScreen
import com.familyrecipebox.app.util.BootTrace
import com.familyrecipebox.app.util.CrashReporter

/**
 * 应用导航图
 */
@Composable
fun RecipeCardsApp() {
    val navController = rememberNavController()

    val context = LocalContext.current

    // 上次运行若异常退出，这里把诊断信息展示出来。
    // 目的是在没有 adb / Android Studio 的情况下也能拿到确切的失败原因。
    // 内容由两部分组成：启动阶段记录（能抓到 provider 阶段的失败）+ 异常堆栈。
    var diagnostic by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // 界面组合成功，说明本次启动真正走完了
        BootTrace.complete()

        val crashTrace = CrashReporter.readLastCrash(context)
        val bootTrace = BootTrace.readIncompleteBoot(context)
        val handledError = CrashReporter.readLastError(context)
        diagnostic = listOfNotNull(bootTrace, crashTrace, handledError)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n---------------\n")
    }
    diagnostic?.let { report ->
        StartupCrashDialog(
            report = report,
            onShare = { shareDiagnostic(context, report) },
            onDismiss = {
                CrashReporter.clear(context)
                CrashReporter.clearError(context)
                BootTrace.clear(context)
                diagnostic = null
            }
        )
    }

    // 数据库、仓库、图片仓库与备份管理器都由 Application 持有单例，
    // 避免同一份数据被多个实例各管一摊
    val app = context.applicationContext as RecipeCardsApplication
    val repository = app.repository
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(repository, context)
    )

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = homeViewModel,
                onRecipeClick = { recipeId ->
                    navController.navigate("edit/$recipeId")
                },
                onAddRecipe = { category ->
                    // 把当前浏览的页签带进编辑页，作为新菜谱的默认分类：
                    // 这样保存后返回，新菜谱正好出现在他刚才看的那个页签里，
                    // 不会出现「明明保存成功却找不到」的困惑
                    navController.navigate("edit/0?defaultCategory=$category")
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }

        composable(
            route = "edit/{recipeId}?defaultCategory={defaultCategory}",
            arguments = listOf(
                navArgument("recipeId") { type = NavType.IntType },
                navArgument("defaultCategory") {
                    type = NavType.StringType
                    defaultValue = DEFAULT_RECIPE_CATEGORY
                }
            )
        ) { backStackEntry ->
            val recipeId = backStackEntry.arguments?.getInt("recipeId") ?: 0
            val defaultCategory = backStackEntry.arguments?.getString("defaultCategory")
                ?: DEFAULT_RECIPE_CATEGORY
            val editViewModel: EditViewModel = viewModel(
                key = recipeId.toString(),
                factory = EditViewModel.Factory(
                    recipeId = recipeId,
                    repository = repository,
                    imageStore = app.imageStore,
                    defaultCategory = defaultCategory
                )
            )
            EditScreen(
                viewModel = editViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onResetCategoryNames = {
                    homeViewModel.resetCategoryNames(context)
                }
            )
        }
    }
}

/**
 * 上次启动失败的诊断对话框，展示启动阶段与异常堆栈
 */
@Composable
private fun StartupCrashDialog(
    report: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.startup_crash_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.startup_crash_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.startup_crash_dismiss))
            }
        },
        dismissButton = {
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.startup_crash_share))
            }
        }
    )
}

/**
 * 把诊断信息通过系统分享面板发出去，便于把日志传给开发者定位。
 * 分享失败不应再产生新的异常。
 */
private fun shareDiagnostic(context: Context, report: String) {
    try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.startup_crash_title))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.startup_crash_share))
        )
    } catch (e: Exception) {
        // 无可用分享目标时忽略
    }
}
