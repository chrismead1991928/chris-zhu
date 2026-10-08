package com.familyrecipebox.app.ui.edit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.familyrecipebox.app.R
import com.familyrecipebox.app.share.RecipeShareImageSharer
import com.familyrecipebox.app.share.ShareCardData
import com.familyrecipebox.app.share.ShareCardLabels
import com.familyrecipebox.app.ui.common.INTERNAL_CATEGORIES
import com.familyrecipebox.app.ui.common.categoryDisplayName
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    viewModel: EditViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories = INTERNAL_CATEGORIES
    // 提前解析一次：分类名既要展示，也要写进本地拼装的 AI 提示词
    val categoryName = categoryDisplayName(uiState.category)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.selectImage(it) }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var isSharing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val imageSaveFailed by viewModel.imageSaveFailed.collectAsStateWithLifecycle()
    val imageSaveFailedMessage = stringResource(R.string.photo_save_failed)

    // 照片没能复制进应用私有目录时明确告知用户，
    // 否则他会以为配图已经换好了，回头才发现还是旧图
    LaunchedEffect(imageSaveFailed) {
        if (imageSaveFailed) {
            Toast.makeText(context, imageSaveFailedMessage, Toast.LENGTH_SHORT).show()
            viewModel.consumeImageSaveFailure()
        }
    }

    // ---- 分享长图 ----
    val shareSharer = remember(context) { RecipeShareImageSharer(context) }
    val shareFailedMessage = stringResource(R.string.share_image_failed)
    // 长图上的品牌名统一取自 app_name。
    // 这样图上显示的名字与商店里的应用名一致，看到图的人才能搜到；
    // 页脚标语也复用同一个名字，避免同一张图上出现两个不同的名字。
    val brandName = stringResource(R.string.app_name)
    val shareLabels = ShareCardLabels(
        appName = brandName,
        stepsHeading = stringResource(R.string.steps),
        tipsHeading = stringResource(R.string.tips),
        tagline = stringResource(R.string.share_image_tagline, brandName),
        // 标题为空时用字段名兜底，总比分享出一张没有标题的卡片强
        untitledFallback = stringResource(R.string.recipe_name)
    )

    /**
     * 分享当前正在编辑的内容，而不是数据库里那份。
     *
     * 这是刻意的：用户点了分享，期望看到的就是屏幕上这些文字。
     * 长图渲染在后台线程完成，界面只负责显示一个进度提示。
     */
    val startShare: () -> Unit = {
        scope.launch {
            isSharing = true
            try {
                val intent = shareSharer.createShareIntent(
                    data = ShareCardData(
                        title = uiState.title,
                        imageUri = uiState.imageUri,
                        imageName = uiState.imageName,
                        categoryLabel = categoryName,
                        rating = uiState.rating,
                        steps = uiState.steps,
                        tips = uiState.tips
                    ),
                    labels = shareLabels,
                    chooserTitle = context.getString(R.string.share_recipe)
                )
                if (intent != null) {
                    // 设备上可能一个能接收图片的应用都没有，这里必须容错
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            Toast.makeText(context, shareFailedMessage, Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(context, shareFailedMessage, Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSharing = false
            }
        }
    }

    if (isSharing) {
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
                    Text(stringResource(R.string.share_creating_image))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_recipe)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete(onBack)
                    }
                ) {
                    Text(stringResource(R.string.confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showPromptDialog) {
        PromptDialog(
            title = uiState.title,
            category = uiState.category,
            categoryDisplayName = categoryName,
            steps = uiState.steps,
            tips = uiState.tips,
            onDismiss = { showPromptDialog = false },
            onCopy = { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.prompt), text))
                Toast.makeText(context, R.string.prompt_copied, Toast.LENGTH_SHORT).show()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = startShare,
                        // 生成期间禁用，避免连点堆积出多张待分享的图
                        enabled = !isSharing
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.share_recipe)
                        )
                    }
                    if (!uiState.isNew && uiState.id != 0) {
                        IconButton(
                            onClick = { showDeleteDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete_recipe),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            viewModel.save()
                            onBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = stringResource(R.string.save)
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
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                RecipeImageCard(
                    title = uiState.title,
                    imageName = uiState.imageName,
                    imageUri = uiState.imageUri,
                    onPickImage = { imagePickerLauncher.launch("image/*") },
                    onPromptClick = { showPromptDialog = true }
                )
            }

            item {
                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = viewModel::updateTitle,
                    label = { Text(stringResource(R.string.recipe_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                CategoryDropdown(
                    categories = categories,
                    selectedCategory = uiState.category,
                    onCategorySelected = viewModel::updateCategory
                )
            }

            item {
                Column {
                    SectionHeader(title = stringResource(R.string.rating))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StarRatingBar(
                            rating = uiState.rating,
                            onRatingChange = viewModel::updateRating
                        )
                        Text(
                            text = "%.1f".format(uiState.rating),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.steps))
            }

            items(
                count = uiState.steps.size,
                key = { index -> "step_$index" }
            ) { index ->
                EditableListItem(
                    index = index,
                    value = uiState.steps[index],
                    placeholder = stringResource(R.string.step_number, index + 1),
                    onValueChange = { viewModel.updateStep(index, it) },
                    onDelete = { viewModel.removeStep(index) }
                )
            }

            item {
                OutlinedButton(
                    onClick = viewModel::addStep,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_step))
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionHeader(title = stringResource(R.string.tips))
            }

            items(
                count = uiState.tips.size,
                key = { index -> "tip_$index" }
            ) { index ->
                EditableListItem(
                    index = index,
                    value = uiState.tips[index],
                    placeholder = stringResource(R.string.tip_number, index + 1),
                    onValueChange = { viewModel.updateTip(index, it) },
                    onDelete = { viewModel.removeTip(index) }
                )
            }

            item {
                OutlinedButton(
                    onClick = viewModel::addTip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_tip))
                }
            }

            item {
                CreatedAtField(
                    createdAt = uiState.createdAt,
                    onCreatedAtChange = viewModel::updateCreatedAt
                )
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun CreatedAtField(
    createdAt: Long,
    onCreatedAtChange: (Long) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var dateText by remember { mutableStateOf(dateFormat.format(Date(createdAt))) }

    LaunchedEffect(createdAt) {
        dateText = dateFormat.format(Date(createdAt))
    }

    Column {
        SectionHeader(title = stringResource(R.string.created_at))
        OutlinedTextField(
            value = dateText,
            onValueChange = { text ->
                dateText = text
                try {
                    dateFormat.parse(text)?.let { onCreatedAtChange(it.time) }
                } catch (_: Exception) {
                    // 格式错误时保留原值，不更新 ViewModel
                }
            },
            label = { Text(stringResource(R.string.date_format_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = categoryDisplayName(selectedCategory),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, true)
        )
        ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(categoryDisplayName(category)) },
                        onClick = {
                            onCategorySelected(category)
                            expanded = false
                        }
                    )
                }
            }
    }
}

@Composable
private fun StarRatingBar(
    rating: Float,
    onRatingChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val starSize = 36.dp
    val totalWidth = starSize * 5
    val starSizePx = with(density) { starSize.toPx() }
    var rowWidthPx by remember { mutableIntStateOf(0) }

    fun ratingFromX(x: Float): Float {
        val raw = x / starSizePx
        val half = (raw * 2).roundToInt() / 2f
        return half.coerceIn(0f, 5f)
    }

    Box(
        modifier = modifier
            .width(totalWidth)
            .height(starSize)
            .onSizeChanged { rowWidthPx = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    val x = change.position.x.coerceIn(0f, rowWidthPx.toFloat())
                    val newRating = ratingFromX(x)
                    if (newRating != rating) onRatingChange(newRating)
                }
            }
    ) {
        Row {
            repeat(5) { index ->
                val starValue = index + 1
                val icon = when {
                    rating >= starValue -> Icons.Filled.Star
                    rating >= starValue - 0.5f -> Icons.AutoMirrored.Filled.StarHalf
                    else -> Icons.Outlined.Star
                }
                IconButton(
                    onClick = { onRatingChange(starValue.toFloat()) },
                    modifier = Modifier.size(starSize)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (rating >= starValue - 0.5f) Color(0xFFFFB300)
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(starSize)
                    )
                }
            }
        }
    }
}

/**
 * 菜谱配图卡片。
 *
 * 只保留两件事：从相册选图、复制提示词。
 *
 * 「复制提示词」是纯本地的文本拼装（[ImagePromptSkill]），不发出任何网络请求、不产生任何费用：
 * 用户可以把拼好的提示词拿到任意第三方生图工具里使用。
 * 之所以保留它，是因为它零成本却能覆盖「想给菜色配张图」的需求。
 */
@Composable
private fun RecipeImageCard(
    title: String,
    imageName: String,
    imageUri: String?,
    onPickImage: () -> Unit,
    onPromptClick: () -> Unit
) {
    val fallbackResId = rememberFallbackResId(imageName)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (imageUri.isNullOrBlank()) {
                Image(
                    painter = painterResource(id = fallbackResId),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                )
            } else {
                AsyncImage(
                    model = imageUri,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(id = fallbackResId),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    CompactActionButton(
                        icon = Icons.Filled.ContentCopy,
                        label = stringResource(R.string.prompt),
                        onClick = onPromptClick
                    )

                    CompactActionButton(
                        icon = Icons.Filled.Image,
                        label = stringResource(R.string.pick_image),
                        onClick = onPickImage
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun PromptDialog(
    title: String,
    category: String,
    categoryDisplayName: String,
    steps: List<String>,
    tips: List<String>,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    val initialPrompt = remember {
        ImagePromptSkill.buildPrompt(title, category, categoryDisplayName, steps, tips)
    }
    var editedPrompt by remember { mutableStateOf(initialPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt)) },
        text = {
            OutlinedTextField(
                value = editedPrompt,
                onValueChange = { editedPrompt = it },
                label = { Text(stringResource(R.string.editable_prompt)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCopy(editedPrompt)
                    onDismiss()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.copy_prompt))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun rememberFallbackResId(imageName: String): Int {
    val context = LocalContext.current
    return remember(imageName) {
        context.resources.getIdentifier(imageName, "drawable", context.packageName)
            .takeIf { it != 0 } ?: R.drawable.tomato_egg
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun EditableListItem(
    index: Int,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(placeholder) },
            modifier = Modifier.weight(1f)
        )

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
