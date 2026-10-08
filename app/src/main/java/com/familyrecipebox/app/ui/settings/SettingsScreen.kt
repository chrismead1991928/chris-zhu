package com.familyrecipebox.app.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StarRate
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.familyrecipebox.app.R
import com.familyrecipebox.app.RecipeCardsApplication
import com.familyrecipebox.app.backup.BackupExportResult
import com.familyrecipebox.app.backup.BackupFormat
import com.familyrecipebox.app.backup.BackupImportResult
import com.familyrecipebox.app.backup.BackupPrepareResult
import com.familyrecipebox.app.backup.BackupRejectionReason
import com.familyrecipebox.app.backup.ImportMode
import com.familyrecipebox.app.backup.PendingBackup
import com.familyrecipebox.app.billing.FreeTierLimiter
import com.familyrecipebox.app.ui.premium.PremiumDialog
import com.familyrecipebox.app.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页。
 *
 * 纯本地应用，因此这里没有账号区块，也没有云同步区块：
 * 从产品方向确定「一次性买断 + 数据只存本机」之后，这两者都失去了存在理由。
 * 保留账号入口会让用户为一个不做任何事的登录付出隐私成本。
 *
 * 删掉云同步之后，「备份与恢复」成了用户唯一能自己掌握的数据出口，
 * 因此它被放在列表最前面：换机之前必须先找得到它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onResetCategoryNames: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as RecipeCardsApplication
    val billingManager = remember { app.billingManager }
    val billingState by billingManager.state.collectAsState()
    val backupManager = remember { app.backupManager }
    val scope = rememberCoroutineScope()

    var showGuide by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showPremiumDialog by remember { mutableStateOf(false) }

    // ---- 备份与恢复的状态 ----
    var isBusy by remember { mutableStateOf(false) }
    var pendingBackup by remember { mutableStateOf<PendingBackup?>(null) }
    var importResult by remember { mutableStateOf<BackupImportResult?>(null) }
    var localRecipeCount by remember { mutableIntStateOf(0) }

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    // 应用名统一取自 app_name：它同时是桌面图标名、长图上的品牌名与商店里的名字。
    // 需要用到名字的文案一律通过格式参数注入，避免把名字硬写进译文后与商店名对不上。
    val brandName = stringResource(R.string.app_name)

    // 协程回调里拿不到 stringResource，所以先把提示文案取出来
    val msgNothingToExport = stringResource(R.string.backup_export_empty)
    val msgExportFailed = stringResource(R.string.backup_export_failed)
    val msgImportFailed = stringResource(R.string.backup_import_failed)
    val msgNotABackup = stringResource(R.string.backup_import_invalid, brandName)
    val msgNoRecipesInBackup = stringResource(R.string.backup_import_empty)
    val msgNewerFormat = stringResource(R.string.backup_import_newer)

    val showToast: (String) -> Unit = remember(context) {
        { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    val exportContract = remember {
        ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)
    }
    val importContract = remember {
        ActivityResultContracts.OpenDocument()
    }

    val exportLauncher = rememberLauncherForActivityResult(contract = exportContract) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            when (val result = backupManager.export(target)) {
                is BackupExportResult.NothingToExport -> showToast(msgNothingToExport)
                is BackupExportResult.Failure -> showToast(msgExportFailed)
                is BackupExportResult.Success -> {
                    val message = if (result.unreadableImages > 0) {
                        context.getString(
                            R.string.backup_export_partial,
                            result.recipeCount,
                            result.imageCount,
                            result.unreadableImages
                        )
                    } else {
                        context.getString(
                            R.string.backup_export_success,
                            result.recipeCount,
                            result.imageCount
                        )
                    }
                    showToast(message)
                }
            }
            isBusy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(contract = importContract) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            when (val result = backupManager.prepare(source)) {
                is BackupPrepareResult.Ready -> pendingBackup = result.pending
                is BackupPrepareResult.Rejected -> showToast(
                    when (result.reason) {
                        BackupRejectionReason.NOT_A_BACKUP,
                        BackupRejectionReason.TOO_LARGE -> msgNotABackup

                        BackupRejectionReason.NEWER_FORMAT -> msgNewerFormat
                        BackupRejectionReason.NO_RECIPES -> msgNoRecipesInBackup
                    }
                )
                is BackupPrepareResult.Failure -> showToast(msgImportFailed)
            }
            isBusy = false
        }
    }

    // 算出本机现有条数，用于在选择「整库替换」时说清会删掉多少东西
    LaunchedEffect(pendingBackup) {
        localRecipeCount = if (pendingBackup == null) {
            0
        } else {
            withContext(Dispatchers.IO) { app.database.recipeCardDao().count() }
        }
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(stringResource(R.string.guide_title)) },
            text = {
                Text(
                    // 只注入免费版上限：买断后的菜谱数量在文案里表述为「无限」，
                    // 因此不再传内部安全上限 PREMIUM_RECIPE_LIMIT
                    stringResource(
                        R.string.guide_content,
                        FreeTierLimiter.FREE_RECIPE_LIMIT
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showGuide = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            currentLanguage = LocaleHelper.getCurrentLanguage(context),
            onDismiss = { showLanguagePicker = false },
            onLanguageSelected = { code ->
                LocaleHelper.setLanguage(context, code)
                showLanguagePicker = false
            }
        )
    }

    if (showPremiumDialog) {
        PremiumDialog(
            billingManager = billingManager,
            onDismiss = { showPremiumDialog = false }
        )
    }

    pendingBackup?.let { pending ->
        ImportOptionsDialog(
            pending = pending,
            localRecipeCount = localRecipeCount,
            onDismiss = {
                // 解析结果里还留着临时照片，取消时必须清掉
                pending.discard()
                pendingBackup = null
            },
            onConfirm = { mode ->
                scope.launch {
                    isBusy = true
                    try {
                        // restore 内部会在 finally 里丢弃暂存内容，
                        // 这里把状态置空即可，不要再 discard 一次
                        importResult = backupManager.restore(pending, mode)
                        pendingBackup = null
                    } catch (e: Exception) {
                        pendingBackup = null
                        showToast(msgImportFailed)
                    }
                    isBusy = false
                }
            }
        )
    }

    importResult?.let { result ->
        ImportResultDialog(
            result = result,
            isPremium = billingState.isPremium,
            onDismiss = { importResult = null },
            onUnlock = {
                importResult = null
                showPremiumDialog = true
            }
        )
    }

    if (isBusy) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.backup_working))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSectionTitle(stringResource(R.string.backup_title))
            SettingsListItem(
                icon = Icons.Filled.Save,
                title = stringResource(R.string.backup_export),
                subtitle = stringResource(R.string.backup_export_summary),
                onClick = { exportLauncher.launch(BackupFormat.suggestedFileName()) }
            )
            SettingsListItem(
                icon = Icons.Filled.Restore,
                title = stringResource(R.string.backup_import),
                subtitle = stringResource(R.string.backup_import_summary),
                onClick = {
                    // 不按 MIME 过滤：备份可能经由云盘、邮件、聊天工具转存，
                    // 类型信息常在转存过程中丢失，过滤反而会让用户「找不到自己的备份」。
                    // 文件是否可用由解析阶段严格校验。
                    importLauncher.launch(arrayOf("*/*"))
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionTitle(stringResource(R.string.user_guide))
            SettingsListItem(
                icon = Icons.AutoMirrored.Filled.Help,
                title = stringResource(R.string.user_guide),
                subtitle = stringResource(R.string.guide_title),
                onClick = { showGuide = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionTitle(stringResource(R.string.premium_title))
            val premiumSubtitle = if (billingState.isPremium) {
                stringResource(R.string.premium_unlocked)
            } else {
                stringResource(R.string.premium_unlock_summary)
            }
            SettingsListItem(
                icon = Icons.Filled.WorkspacePremium,
                title = stringResource(R.string.premium_unlock),
                subtitle = premiumSubtitle,
                onClick = { showPremiumDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionTitle(stringResource(R.string.share_app))
            SettingsListItem(
                icon = Icons.Filled.Share,
                title = stringResource(R.string.share_app),
                subtitle = stringResource(R.string.share_app_summary, brandName),
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        // 推荐语里的应用名同样取自 app_name：
                        // 收件人按这个名字去商店搜才能搜到，写死英文名会让非英语用户扑空
                        putExtra(
                            Intent.EXTRA_TEXT,
                            context.getString(
                                R.string.share_app_message,
                                context.getString(R.string.app_name)
                            )
                        )
                    }
                    val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_app))
                    context.startActivity(chooser)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionTitle(stringResource(R.string.rate_app))
            SettingsListItem(
                icon = Icons.Filled.StarRate,
                title = stringResource(R.string.rate_app),
                subtitle = stringResource(R.string.rate_app_summary),
                onClick = {
                    Toast.makeText(context, R.string.coming_soon, Toast.LENGTH_SHORT).show()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionTitle(stringResource(R.string.language))
            SettingsListItem(
                icon = Icons.Filled.Language,
                title = stringResource(R.string.language),
                subtitle = LocaleHelper.getLanguageDisplayName(
                    LocaleHelper.getCurrentLanguage(context)
                ),
                onClick = { showLanguagePicker = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionTitle(stringResource(R.string.edit_category_name))
            SettingsListItem(
                icon = Icons.Filled.Refresh,
                title = stringResource(R.string.reset_category_names),
                subtitle = stringResource(R.string.category_name_hint),
                onClick = {
                    onResetCategoryNames()
                    Toast.makeText(
                        context,
                        context.getString(R.string.reset_category_names),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsSectionTitle(stringResource(R.string.app_info))
            SettingsListItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.app_info),
                subtitle = stringResource(R.string.version, versionName),
                onClick = { }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 让用户在「合并」与「整库替换」之间做选择。
 *
 * 默认选中合并：它是无损的，而替换会先删掉本机数据。
 * 破坏性选项必须由用户主动点选，并当场看到会删掉多少条。
 */
@Composable
private fun ImportOptionsDialog(
    pending: PendingBackup,
    localRecipeCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (ImportMode) -> Unit
) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }

    // 与编辑页的建档时间保持同一种写法，避免同一份数据在应用里出现两种日期样式
    val exportedAt = remember(pending) {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        formatter.format(Date(pending.manifest.exportedAt))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_confirm_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.backup_import_confirm_body,
                        pending.manifest.recipeCount,
                        pending.manifest.imageCount,
                        exportedAt
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                ImportModeOption(
                    title = stringResource(R.string.backup_import_merge),
                    description = stringResource(R.string.backup_import_merge_desc),
                    selected = mode == ImportMode.MERGE,
                    destructive = false,
                    onSelect = { mode = ImportMode.MERGE }
                )
                ImportModeOption(
                    title = stringResource(R.string.backup_import_replace),
                    description = stringResource(
                        R.string.backup_import_replace_desc,
                        localRecipeCount
                    ),
                    selected = mode == ImportMode.REPLACE,
                    destructive = true,
                    onSelect = { mode = ImportMode.REPLACE }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mode) }) {
                Text(stringResource(R.string.backup_import_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ImportModeOption(
    title: String,
    description: String,
    selected: Boolean,
    destructive: Boolean,
    onSelect: () -> Unit
) {
    val accent = if (destructive && selected) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = accent
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (destructive && selected) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 导入结果。
 *
 * 当恢复后的条数超过免费版上限时，这里必须把话说清楚：
 * 菜谱**一条都不会少**，只是不能再新增，直到解锁完整版。
 * 沉默地让用户日后撞上上限，比当场说明要糟糕得多。
 */
@Composable
private fun ImportResultDialog(
    result: BackupImportResult,
    isPremium: Boolean,
    onDismiss: () -> Unit,
    onUnlock: () -> Unit
) {
    val overLimit = !isPremium && result.totalRecipes > FreeTierLimiter.FREE_RECIPE_LIMIT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_success_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.backup_import_success,
                        result.added,
                        result.updated,
                        result.skipped,
                        result.imagesRestored
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (overLimit) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            R.string.backup_import_over_limit,
                            result.totalRecipes
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (overLimit) {
                TextButton(onClick = onUnlock) {
                    Text(stringResource(R.string.premium_unlock))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = if (overLimit) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ok))
                }
            }
        } else {
            null
        }
    )
}

@Composable
private fun LanguagePickerDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val languages = LocaleHelper.getSupportedLanguages()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                languages.forEach { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(code) }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = code == currentLanguage,
                            onClick = { onLanguageSelected(code) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = LocaleHelper.getLanguageDisplayName(code),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text = subtitle) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
