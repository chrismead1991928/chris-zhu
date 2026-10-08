package com.familyrecipebox.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.familyrecipebox.app.R
import com.familyrecipebox.app.RecipeCardsApplication
import com.familyrecipebox.app.billing.BillingManager
import com.familyrecipebox.app.billing.FreeTierLimiter
import com.familyrecipebox.app.data.RecipeCard
import com.familyrecipebox.app.ui.common.INTERNAL_CATEGORIES
import com.familyrecipebox.app.ui.premium.RecipeLimitDialog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val MIN_COLUMNS = 1
private const val MAX_COLUMNS = 4

/**
 * 距离上限还剩这么多道菜时，开始在标题栏显示用量。
 *
 * 目的是把「意外撞墙」提前变成「已知的期限」：
 * 用户能提前决定要不要付费，而不是加第 21 道时被突然挡住。
 */
private const val FREE_TIER_COUNTER_THRESHOLD = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onRecipeClick: (Int) -> Unit,
    /**
     * 新建菜谱。参数是当前选中的页签，会被带进编辑页作为新菜谱的默认分类，
     * 保证保存后返回时新菜谱就出现在用户眼前这个页签里。
     */
    onAddRecipe: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    val recipes by viewModel.recipes.collectAsStateWithLifecycle()
    val categoryDisplayNames by viewModel.categoryDisplayNames.collectAsStateWithLifecycle()
    val columnCountState = remember { mutableIntStateOf(2) }
    val showIndicatorState = remember { mutableStateOf(false) }
    val previousColumnForIndicator = remember { mutableIntStateOf(columnCountState.intValue) }
    val categories = INTERNAL_CATEGORIES
    val selectedCategory = remember { mutableStateOf(categories.first()) }
    val filteredRecipes by remember(recipes, selectedCategory.value) {
        derivedStateOf {
            recipes.filter { it.category == selectedCategory.value }
        }
    }

    val context = LocalContext.current
    val billingManager = remember { (context.applicationContext as RecipeCardsApplication).billingManager }
    val billingState by billingManager.state.collectAsStateWithLifecycle()
    val freeTierLimiter = remember { FreeTierLimiter(context) }

    // 上限只在这里取一次，判定与展示共用同一个值，避免两处各算一套
    val recipeLimit = freeTierLimiter.getRecipeLimit(billingState.isPremium)
    val freeSlotsLeft = (recipeLimit - recipes.size).coerceAtLeast(0)
    // 平时不占位，只在接近上限时出现，避免变成常驻噪音
    val showFreeTierCounter = !billingState.isPremium &&
        freeSlotsLeft <= FREE_TIER_COUNTER_THRESHOLD

    val gridState = rememberLazyGridState()
    var draggingRecipe by remember { mutableStateOf<RecipeCard?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var randomRecipe by remember { mutableStateOf<RecipeCard?>(null) }
    var lastDiceClick by remember { mutableLongStateOf(0L) }

    var renameCategoryTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showLimitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(columnCountState.intValue) {
        if (columnCountState.intValue != previousColumnForIndicator.intValue) {
            previousColumnForIndicator.intValue = columnCountState.intValue
            showIndicatorState.value = true
            delay(600)
            showIndicatorState.value = false
        }
    }

    fun calculateTargetIndex(): Int? {
        val recipe = draggingRecipe ?: return null
        val layoutInfo = gridState.layoutInfo
        val draggedInfo = layoutInfo.visibleItemsInfo.find { it.key == recipe.id } ?: return null
        val centerX = draggedInfo.offset.x + draggedInfo.size.width / 2f + dragOffset.x
        val centerY = draggedInfo.offset.y + draggedInfo.size.height / 2f + dragOffset.y
        val target = layoutInfo.visibleItemsInfo.find { info ->
            info.key != recipe.id &&
                    centerX >= info.offset.x &&
                    centerX < info.offset.x + info.size.width &&
                    centerY >= info.offset.y &&
                    centerY < info.offset.y + info.size.height
        } ?: return null
        val targetCenterX = target.offset.x + target.size.width / 2f
        return if (centerX < targetCenterX) target.index else target.index + 1
    }

    renameCategoryTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameCategoryTarget = null },
            title = { Text(stringResource(R.string.edit_category_name)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.category_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameCategory(target, renameText)
                        renameCategoryTarget = null
                    }
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameCategoryTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showLimitDialog) {
        RecipeLimitDialog(
            billingManager = billingManager,
            limit = recipeLimit,
            onDismiss = { showLimitDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    if (now - lastDiceClick < 350 && filteredRecipes.isNotEmpty()) {
                                        randomRecipe = filteredRecipes.random()
                                    }
                                    lastDiceClick = now
                                }
                            ) {
                                DiceIcon(
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = stringResource(R.string.random_pick_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp,
                                lineHeight = 10.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (freeTierLimiter.canAddRecipe(recipes.size, billingState.isPremium)) {
                                    onAddRecipe(selectedCategory.value)
                                } else {
                                    showLimitDialog = true
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.add_recipe),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        if (showFreeTierCounter) {
                            FreeTierCounterBadge(
                                used = recipes.size,
                                limit = recipeLimit,
                                reachedLimit = freeSlotsLeft == 0
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                },
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        },
        bottomBar = {
            CategoryBottomBar(
                categories = categories,
                displayNames = categoryDisplayNames,
                selectedCategory = selectedCategory.value,
                onCategorySelected = { selectedCategory.value = it },
                onCategoryLongPress = { category ->
                    renameCategoryTarget = category
                    renameText = categoryDisplayNames[category] ?: category
                }
            )
        }
    ) { innerPadding ->
        val columnCount = columnCountState.intValue
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(Unit) {
                    val scaleState = mutableFloatStateOf(1f)
                    val startColumnState = mutableIntStateOf(columnCountState.intValue)
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            if (scaleState.floatValue == 1f) {
                                startColumnState.intValue = columnCountState.intValue
                            }
                            scaleState.floatValue *= zoom
                            val newCount = (startColumnState.intValue / scaleState.floatValue)
                                .roundToInt()
                                .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                            if (newCount != columnCountState.intValue) {
                                columnCountState.intValue = newCount
                                scaleState.floatValue = 1f
                                startColumnState.intValue = newCount
                            }
                        }
                    }
                    scaleState.floatValue = 1f
                }
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columnCount),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                flingBehavior = rememberFastFlingBehavior(),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = filteredRecipes,
                    key = { it.id }
                ) { recipe ->
                    val isDragging = draggingRecipe?.id == recipe.id
                    RecipeCardItem(
                        recipe = recipe,
                        isDragging = isDragging,
                        dragOffset = if (isDragging) dragOffset else Offset.Zero,
                        onClick = { if (draggingRecipe == null) onRecipeClick(recipe.id) },
                        onLikeClick = { viewModel.toggleLike(recipe) },
                        onDragStart = {
                            draggingRecipe = recipe
                            dragOffset = Offset.Zero
                        },
                        onDrag = { delta ->
                            dragOffset += delta
                        },
                        onDragEnd = {
                            val fromIndex = filteredRecipes.indexOfFirst { it.id == draggingRecipe?.id }
                            val toIndex = calculateTargetIndex()
                            if (fromIndex != -1 && toIndex != null &&
                                toIndex != fromIndex && toIndex != fromIndex + 1
                            ) {
                                viewModel.moveRecipeInCategory(
                                    selectedCategory.value,
                                    fromIndex,
                                    toIndex
                                )
                            }
                            draggingRecipe = null
                            dragOffset = Offset.Zero
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = showIndicatorState.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                ZoomIndicator(columnCount = columnCount)
            }

            randomRecipe?.let { recipe ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) { detectTapGestures { randomRecipe = null } }
                        .padding(horizontal = 32.dp, vertical = 48.dp)
                        .zIndex(2f),
                    contentAlignment = Alignment.Center
                ) {
                    RandomRecipeCard(recipe = recipe)
                }
            }
        }
    }
}

/**
 * 标题栏里的免费版用量徽标（形如 18/20）。
 *
 * 只在接近上限时出现。目的是让「还剩几道」在点 + 之前就可见，
 * 而不是等被挡住才知道有上限——突然出现的墙才是差评的来源。
 *
 * 颜色刻意保持克制：未到上限时用中性色，用满后只换成主色，
 * 不用错误红去渲染一个正常的商业规则。
 */
@Composable
private fun FreeTierCounterBadge(
    used: Int,
    limit: Int,
    reachedLimit: Boolean
) {
    Surface(
        color = if (reachedLimit) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.free_tier_counter, used, limit),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = if (reachedLimit) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryBottomBar(
    categories: List<String>,
    displayNames: Map<String, String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onCategoryLongPress: (String) -> Unit
) {
    val icons = listOf(
        Icons.Filled.RestaurantMenu,
        Icons.Filled.Spa,
        Icons.Filled.AcUnit,
        Icons.Filled.MoreHoriz
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEachIndexed { index, category ->
                val selected = category == selectedCategory
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = { onCategorySelected(category) },
                            onLongClick = { onCategoryLongPress(category) }
                        )
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = icons[index],
                        contentDescription = displayNames[category] ?: category,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = displayNames[category] ?: category,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberFastFlingBehavior(): FlingBehavior {
    val decay = rememberSplineBasedDecay<Float>()
    return remember(decay) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val boostedVelocity = initialVelocity * 1.6f
                var lastValue = 0f
                AnimationState(
                    initialValue = 0f,
                    initialVelocity = boostedVelocity
                ).animateDecay(decay) {
                    val delta = value - lastValue
                    lastValue = value
                    scrollBy(delta)
                }
                return boostedVelocity
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeCardItem(
    recipe: RecipeCard,
    isDragging: Boolean,
    dragOffset: Offset,
    onClick: () -> Unit,
    onLikeClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val imageResId = rememberFallbackResId(recipe.imageName)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                scaleX = if (isDragging) 1.05f else 1f
                scaleY = if (isDragging) 1.05f else 1f
                translationX = dragOffset.x
                translationY = dragOffset.y
                alpha = if (isDragging) 0.95f else 1f
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStart(offset) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
            .combinedClickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (recipe.imageUri.isNullOrBlank()) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = imageResId),
                    contentDescription = recipe.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                )
            } else {
                AsyncImage(
                    model = recipe.imageUri,
                    contentDescription = recipe.title,
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(id = imageResId),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                )
            }

            if (recipe.rating > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .padding(end = 40.dp)
                ) {
                    repeat(recipe.rating.toInt()) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Text(
                text = recipe.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onLikeClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (recipe.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = stringResource(R.string.like),
                    tint = if (recipe.isLiked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun RandomRecipeCard(
    recipe: RecipeCard,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shake")
    val rotation by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(220, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shake"
    )
    val context = LocalContext.current
    val imageResId = rememberFallbackResId(recipe.imageName)

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { rotationZ = rotation }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            ) {
                if (recipe.imageUri.isNullOrBlank()) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = imageResId),
                        contentDescription = recipe.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = recipe.imageUri,
                        contentDescription = recipe.title,
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(id = imageResId),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = recipe.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(
                    text = recipe.category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (recipe.rating > 0) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "%.1f".format(recipe.rating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ZoomIndicator(columnCount: Int) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        )
    ) {
        Text(
            text = stringResource(R.string.zoom_indicator, columnCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

/**
 * 拟物风格骰子图标：带圆角立方体、高光、阴影与五点骰面。
 */
@Composable
private fun DiceIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Box(modifier = modifier) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val corner = width * 0.18f
            val bodyLeft = width * 0.12f
            val bodyTop = height * 0.10f
            val bodySize = Size(
                width = width * 0.76f,
                height = height * 0.76f
            )

            // 底部投影
            drawCircle(
                color = Color.Black.copy(alpha = 0.14f),
                radius = width * 0.34f,
                center = Offset(width * 0.5f, height * 0.62f)
            )

            // 骰子主体（浅灰白）
            drawRoundRect(
                color = Color(0xFFF2F2F2),
                topLeft = Offset(bodyLeft, bodyTop),
                size = bodySize,
                cornerRadius = CornerRadius(corner, corner)
            )

            // 顶部高光
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(bodyLeft + width * 0.03f, bodyTop + height * 0.03f),
                size = Size(
                    width = bodySize.width - width * 0.06f,
                    height = bodySize.height * 0.42f
                ),
                cornerRadius = CornerRadius(corner * 0.85f, corner * 0.85f)
            )

            // 底部暗部
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.08f),
                topLeft = Offset(bodyLeft + width * 0.03f, bodyTop + bodySize.height * 0.48f),
                size = Size(
                    width = bodySize.width - width * 0.06f,
                    height = bodySize.height * 0.46f
                ),
                cornerRadius = CornerRadius(corner * 0.85f, corner * 0.85f)
            )

            // 主题色微光描边
            drawRoundRect(
                color = tint.copy(alpha = 0.25f),
                topLeft = Offset(bodyLeft, bodyTop),
                size = bodySize,
                cornerRadius = CornerRadius(corner, corner),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = width * 0.025f)
            )

            // 外描边
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.18f),
                topLeft = Offset(bodyLeft, bodyTop),
                size = bodySize,
                cornerRadius = CornerRadius(corner, corner),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = width * 0.018f)
            )

            // 五点骰面
            val pipRadius = width * 0.06f
            val pipColor = Color(0xFF333333)
            val cx = width * 0.5f
            val cy = height * 0.46f
            val offset = width * 0.17f

            listOf(
                Offset(cx, cy),
                Offset(cx - offset, cy - offset),
                Offset(cx + offset, cy - offset),
                Offset(cx - offset, cy + offset),
                Offset(cx + offset, cy + offset)
            ).forEach { center ->
                drawCircle(
                    color = Color.Black.copy(alpha = 0.22f),
                    radius = pipRadius * 1.05f,
                    center = center.copy(y = center.y + height * 0.01f)
                )
                drawCircle(color = pipColor, radius = pipRadius, center = center)
            }
        }
    }
}

@Composable
private fun rememberFallbackResId(imageName: String): Int {
    val context = LocalContext.current
    return remember(imageName) {
        context.resources.getIdentifier(imageName, "drawable", context.packageName)
            .takeIf { it != 0 } ?: R.drawable.tomato_egg
    }
}
