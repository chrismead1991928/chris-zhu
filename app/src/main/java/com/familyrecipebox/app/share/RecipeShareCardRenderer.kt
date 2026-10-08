package com.familyrecipebox.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.familyrecipebox.app.R
import java.util.Locale
import kotlin.math.max

/**
 * 长图要展示的内容。
 *
 * 刻意不复用 Room 实体：渲染只需要「给人看的那部分」，
 * 传入实体反而会把同步元数据之类的内部字段带进渲染逻辑。
 */
data class ShareCardData(
    val title: String,
    val imageUri: String?,
    val imageName: String,
    val categoryLabel: String,
    val rating: Float,
    val steps: List<String>,
    val tips: List<String>
)

/**
 * 长图上的文字标签。全部由调用方从 string resources 取好传进来，
 * 让渲染器完全不依赖 Context 的字符串体系，换语言只影响传入的值。
 */
data class ShareCardLabels(
    val appName: String,
    val stepsHeading: String,
    val tipsHeading: String,
    val tagline: String,
    /** 标题为空时的兜底文字，避免分享出一张没有标题的卡片 */
    val untitledFallback: String
)

/**
 * 把一道菜谱画成一张竖向长图。
 *
 * 为什么手写 Canvas 而不是「截屏」或渲染一个不可见的 ComposeView：
 * - 分享图需要的是**品牌化的版面**（大标题、编号步骤、页脚），而不是应用界面的截图；
 * - 脱离屏幕的 ComposeView 需要凑齐生命周期与 composition 环境，在无界面场景下很脆弱；
 * - 手写绘制没有环境依赖，可以在任意线程跑，尺寸也完全可控。
 *
 * 渲染分两步，这是本类最关键的结构：
 * 1. **先排版**：构建全部 [StaticLayout] 与绘制指令，同时累加出精确的最终高度；
 * 2. **再绘制**：按已算好的高度创建 Bitmap，把指令逐条画上去。
 *
 * 先排版再绘制是必须的：Bitmap 高度在创建时就要确定，而高度取决于文字折行结果；
 * 如果反过来「先创建再测量」，就只能开一张超高画布再裁剪，白白吃掉几倍内存。
 */
object RecipeShareCardRenderer {

    /** 输出宽度：1080 是社交平台的常见安全宽度，也能保证文字足够清晰 */
    const val WIDTH = 1080

    private const val OUTER_MARGIN = 44f
    private const val CARD_PADDING = 56f
    private const val CARD_RADIUS = 40f
    private const val CONTENT_WIDTH = WIDTH - OUTER_MARGIN * 2 - CARD_PADDING * 2
    private const val PHOTO_RADIUS = 28f
    private const val PHOTO_HEIGHT = CONTENT_WIDTH * 10f / 16f

    /**
     * 内容区的高度上限。超过之后不再追加步骤与要点，直接收尾。
     *
     * 存在的理由：Bitmap 内存 = 宽 × 高 × 4 字节，不设上限的话，
     * 一段极端冗长的菜谱就能在低端设备上直接 OOM。
     * 按这个上限，最大位图约 1080×7000，占用约 29MB，属于可接受范围。
     */
    private const val MAX_CONTENT_Y = 6600f

    // 与 ui/theme/Color.kt 的暖琥珀色板保持一致，让分享图和应用界面是同一套视觉
    private const val COLOR_BACKGROUND = 0xFFFDFBF7.toInt()
    private const val COLOR_CARD = 0xFFFFFFFF.toInt()
    private const val COLOR_BORDER = 0xFFE8E0D5.toInt()
    private const val COLOR_PRIMARY = 0xFFD97706.toInt()
    private const val COLOR_PRIMARY_DARK = 0xFFB45309.toInt()
    private const val COLOR_TITLE = 0xFF2C2420.toInt()
    private const val COLOR_BODY = 0xFF3A322C.toInt()
    private const val COLOR_MUTED = 0xFF7A6B5D.toInt()
    private const val COLOR_CHIP_BG = 0xFFF5F0E8.toInt()

    private const val SIZE_APP_NAME = 28f
    private const val SIZE_TITLE = 66f
    private const val SIZE_CHIP = 30f
    private const val SIZE_RATING = 32f
    private const val SIZE_HEADING = 32f
    private const val SIZE_STEP_NUMBER = 32f
    private const val SIZE_STEP_TEXT = 38f
    private const val SIZE_TIP_TEXT = 36f
    private const val SIZE_TAGLINE = 26f

    private const val STEP_BADGE_SIZE = 54f
    private const val CHIP_HEIGHT = 62f

    /** 解码目标宽度：绘制宽度约 880px，取 2 倍留出高清屏观感余量 */
    private const val TARGET_DECODE_WIDTH = 1760

    /**
     * 渲染长图。失败（含内存不足）返回 null，由调用方给出用户提示。
     *
     * 同步执行且不做线程切换：调用方负责放到后台线程。
     */
    fun render(context: Context, data: ShareCardData, labels: ShareCardLabels): Bitmap? {
        var photo: Bitmap? = null
        return try {
            photo = loadPhoto(context, data)
            val card = buildCard(data, labels, photo != null)
            val bitmap = Bitmap.createBitmap(WIDTH, card.totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(COLOR_BACKGROUND)
            card.draw(canvas, photo)
            bitmap
        } catch (e: OutOfMemoryError) {
            // 长图是大位图，低端设备上确有失败可能；宁可分享失败也不能让应用被杀掉
            null
        } catch (e: Exception) {
            null
        } finally {
            // 已经画进结果位图，这份源图用完即弃
            photo?.recycle()
        }
    }

    /**
     * 一条绘制指令。用「先记录指令、再统一执行」的方式，
     * 让排版与绘制共用同一份坐标计算结果，避免两处各算一套导致错位。
     */
    private sealed interface DrawOp {
        fun draw(canvas: Canvas)

        class Text(val layout: StaticLayout, val x: Float, val top: Float) : DrawOp {
            override fun draw(canvas: Canvas) {
                canvas.save()
                canvas.translate(x, top)
                layout.draw(canvas)
                canvas.restore()
            }
        }

        class RoundRect(
            val rect: RectF,
            val radius: Float,
            val color: Int,
            val strokeWidth: Float = 0f,
            val strokeColor: Int = 0
        ) : DrawOp {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                if (strokeWidth > 0f) {
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    this.color = strokeColor
                } else {
                    style = Paint.Style.FILL
                    this.color = color
                }
            }

            override fun draw(canvas: Canvas) {
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }

        class Dot(val cx: Float, val cy: Float, val radius: Float, val color: Int) : DrawOp {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

            override fun draw(canvas: Canvas) {
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }

        class Photo(val rect: RectF, val radius: Float) : DrawOp {
            private var shaderPaint: Paint? = null

            override fun draw(canvas: Canvas) {
                val paint = shaderPaint ?: return
                canvas.save()
                canvas.translate(rect.left, rect.top)
                canvas.drawRoundRect(0f, 0f, rect.width(), rect.height(), radius, radius, paint)
                canvas.restore()
            }

            /**
             * 建立居中裁切的着色器：按「铺满矩形」的比例缩放，再把溢出的部分居中挪掉，
             * 等价于 ImageView 的 centerCrop，但不需要中间再生成一张裁剪后的位图。
             */
            fun attachSource(source: Bitmap) {
                if (source.width <= 0 || source.height <= 0) return
                val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = max(
                    rect.width() / source.width.toFloat(),
                    rect.height() / source.height.toFloat()
                )
                shader.setLocalMatrix(
                    Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(
                            (rect.width() - source.width * scale) / 2f,
                            (rect.height() - source.height * scale) / 2f
                        )
                    }
                )
                shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    this.shader = shader
                }
            }
        }
    }

    /** 排版结果：一串待执行的绘制指令 + 精确的总高度 */
    private class CardLayout(
        private val ops: List<DrawOp>,
        private val photoOp: DrawOp.Photo?,
        val totalHeight: Int
    ) {
        fun draw(canvas: Canvas, photo: Bitmap?) {
            if (photoOp != null && photo != null) photoOp.attachSource(photo)
            ops.forEach { it.draw(canvas) }
        }
    }

    private fun buildCard(
        data: ShareCardData,
        labels: ShareCardLabels,
        hasPhoto: Boolean
    ): CardLayout {
        val ops = ArrayList<DrawOp>()
        var photoOp: DrawOp.Photo? = null

        val cardLeft = OUTER_MARGIN
        val cardRight = WIDTH - OUTER_MARGIN
        val contentLeft = cardLeft + CARD_PADDING
        val contentRight = cardRight - CARD_PADDING
        var y = OUTER_MARGIN + CARD_PADDING

        // ---- 顶部：应用名（品牌锚点，让图片离开应用后仍能被认出出处）----
        val appNamePaint = textPaint(SIZE_APP_NAME, COLOR_PRIMARY, Typeface.BOLD).apply {
            letterSpacing = 0.16f
        }
        val appNameLayout = singleLine(labels.appName, appNamePaint, CONTENT_WIDTH)
        ops += DrawOp.Text(appNameLayout, contentLeft, y)
        y += appNameLayout.height + 34f

        // ---- 标题。限行并省略：标题是用户输入，极端长标题不该把版面撑爆 ----
        val titleText = data.title.trim().ifBlank { labels.untitledFallback }
        val titlePaint = textPaint(SIZE_TITLE, COLOR_TITLE, Typeface.BOLD).apply {
            letterSpacing = -0.01f
        }
        val titleLayout = wrapped(titleText, titlePaint, CONTENT_WIDTH, 1.16f, maxLines = 4)
        ops += DrawOp.Text(titleLayout, contentLeft, y)
        y += titleLayout.height + 26f

        // ---- 元信息行：分类胶囊 + 评分 ----
        val ratingText = if (data.rating > 0f) "★ ${formatRating(data.rating)}" else null
        val ratingPaint = textPaint(SIZE_RATING, COLOR_PRIMARY_DARK, Typeface.BOLD)
        val ratingWidth = ratingText?.let { ratingPaint.measureText(it) } ?: 0f

        // 分类名可以被用户改成长句，这里给胶囊留出评分的位置，超出就用省略号
        val chipPaint = textPaint(SIZE_CHIP, COLOR_PRIMARY_DARK, Typeface.BOLD)
        val chipMaxTextWidth = (contentRight - contentLeft - ratingWidth - 48f - 24f)
            .coerceAtLeast(120f)
        val chipLabel = ellipsized(data.categoryLabel, chipPaint, chipMaxTextWidth)
        val chipTextLayout = singleLine(chipLabel, chipPaint, CONTENT_WIDTH)
        val chipWidth = (chipTextLayout.width + 48f).coerceAtMost(CONTENT_WIDTH * 0.72f)
        val chipRect = RectF(contentLeft, y, contentLeft + chipWidth, y + CHIP_HEIGHT)
        ops += DrawOp.RoundRect(chipRect, CHIP_HEIGHT / 2f, COLOR_CHIP_BG)
        ops += DrawOp.Text(
            layout = chipTextLayout,
            x = chipRect.left + 24f,
            top = chipRect.centerY() - chipTextLayout.height / 2f
        )

        if (ratingText != null) {
            val ratingLayout = singleLine(ratingText, ratingPaint, 240f)
            ops += DrawOp.Text(
                layout = ratingLayout,
                x = contentRight - ratingLayout.width,
                top = chipRect.centerY() - ratingLayout.height / 2f
            )
        }
        y = chipRect.bottom + 42f

        // ---- 主图 ----
        if (hasPhoto) {
            val rect = RectF(contentLeft, y, contentRight, y + PHOTO_HEIGHT)
            photoOp = DrawOp.Photo(rect, PHOTO_RADIUS)
            ops += photoOp
            y = rect.bottom
        }

        // ---- 步骤 ----
        val steps = data.steps.map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isNotEmpty()) {
            y += if (hasPhoto) 52f else 8f
            val headingLayout = heading(labels.stepsHeading)
            ops += DrawOp.Text(headingLayout, contentLeft, y)
            y += headingLayout.height + 30f

            val textWidth = CONTENT_WIDTH - STEP_BADGE_SIZE - 20f
            val stepPaint = textPaint(SIZE_STEP_TEXT, COLOR_BODY, Typeface.NORMAL)
            val numberPaint = textPaint(SIZE_STEP_NUMBER, COLOR_PRIMARY, Typeface.BOLD)

            for ((index, step) in steps.withIndex()) {
                if (y > MAX_CONTENT_Y) break
                val stepLayout = wrapped(step, stepPaint, textWidth, 1.32f, maxLines = 8)
                val rowHeight = max(stepLayout.height.toFloat(), STEP_BADGE_SIZE)
                val badgeCy = y + rowHeight / 2f

                ops += DrawOp.Dot(
                    cx = contentLeft + STEP_BADGE_SIZE / 2f,
                    cy = badgeCy,
                    radius = STEP_BADGE_SIZE / 2f,
                    color = COLOR_CHIP_BG
                )
                val numberLayout = singleLine("${index + 1}", numberPaint, STEP_BADGE_SIZE)
                ops += DrawOp.Text(
                    layout = numberLayout,
                    x = contentLeft + (STEP_BADGE_SIZE - numberLayout.width) / 2f,
                    top = badgeCy - numberLayout.height / 2f
                )
                ops += DrawOp.Text(stepLayout, contentLeft + STEP_BADGE_SIZE + 20f, y)

                y += rowHeight + 34f
            }
        }

        // ---- 要点 ----
        val tips = data.tips.map { it.trim() }.filter { it.isNotEmpty() }
        if (tips.isNotEmpty()) {
            y += if (steps.isNotEmpty()) 18f else 8f
            val headingLayout = heading(labels.tipsHeading)
            ops += DrawOp.Text(headingLayout, contentLeft, y)
            y += headingLayout.height + 26f

            val tipPaint = textPaint(SIZE_TIP_TEXT, COLOR_MUTED, Typeface.NORMAL)
            for (tip in tips) {
                if (y > MAX_CONTENT_Y) break
                val tipLayout = wrapped(tip, tipPaint, CONTENT_WIDTH - 34f, 1.3f, maxLines = 8)
                ops += DrawOp.Dot(
                    cx = contentLeft + 8f,
                    cy = y + tipLayout.height / 2f - 2f,
                    radius = 7f,
                    color = COLOR_PRIMARY
                )
                ops += DrawOp.Text(tipLayout, contentLeft + 34f, y)
                y += tipLayout.height + 26f
            }
        }

        // ---- 页脚 ----
        y += 40f
        ops += DrawOp.RoundRect(
            rect = RectF(contentLeft, y, contentRight, y + 2f),
            radius = 1f,
            color = COLOR_BORDER
        )
        y += 30f

        val taglineLayout = singleLine(
            labels.tagline,
            textPaint(SIZE_TAGLINE, COLOR_MUTED, Typeface.NORMAL),
            CONTENT_WIDTH
        )
        ops += DrawOp.Text(taglineLayout, contentLeft, y)
        y += taglineLayout.height + CARD_PADDING

        // 卡片底板必须在最底层，所以插到指令列表最前面
        val cardRect = RectF(cardLeft, OUTER_MARGIN, cardRight, y)
        ops.add(0, DrawOp.RoundRect(cardRect, CARD_RADIUS, COLOR_CARD))
        ops.add(
            1,
            DrawOp.RoundRect(
                rect = cardRect,
                radius = CARD_RADIUS,
                color = 0,
                strokeWidth = 2f,
                strokeColor = COLOR_BORDER
            )
        )

        return CardLayout(ops, photoOp, (y + OUTER_MARGIN).toInt().coerceAtLeast(400))
    }

    private fun heading(text: String): StaticLayout {
        val paint = textPaint(SIZE_HEADING, COLOR_PRIMARY, Typeface.BOLD).apply {
            letterSpacing = 0.12f
        }
        return singleLine(text, paint, CONTENT_WIDTH)
    }

    private fun textPaint(size: Float, color: Int, style: Int): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create("sans-serif", style)
        }

    private fun wrapped(
        text: String,
        paint: TextPaint,
        width: Float,
        lineSpacingMultiplier: Float,
        maxLines: Int
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, lineSpacingMultiplier)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        // 简单断行 + 不断字：菜谱里常出现较长的单词或数字，高质量断行会插入连字符，反而更难读
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()

    private fun singleLine(text: CharSequence, paint: TextPaint, maxWidth: Float): StaticLayout {
        val measured = max(paint.measureText(text, 0, text.length).toInt() + 2, 1)
        val width = measured.coerceAtMost(maxWidth.toInt().coerceAtLeast(1))
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(1)
            .build()
    }

    private fun ellipsized(text: String, paint: TextPaint, maxWidth: Float): CharSequence =
        TextUtils.ellipsize(text, paint, maxWidth.coerceAtLeast(1f), TextUtils.TruncateAt.END)

    private fun formatRating(rating: Float): String =
        if (rating % 1f == 0f) rating.toInt().toString()
        else String.format(Locale.US, "%.1f", rating)

    /**
     * 取菜谱配图。
     *
     * 优先用户照片（应用私有目录里的 file:// 地址，通过 contentResolver 读取，
     * 顺手就能支持任何 scheme）；读不到再退回内置插画，最后兜底到默认图，
     * 保证分享图上总有一张图，而不是一块空白。
     */
    private fun loadPhoto(context: Context, data: ShareCardData): Bitmap? {
        data.imageUri?.takeIf { it.isNotBlank() }?.let { uriString ->
            decodeDownsampled(context, uriString)?.let { return it }
        }

        val fromName = context.resources
            .getIdentifier(data.imageName, "drawable", context.packageName)
        val id = if (fromName != 0) fromName else R.drawable.tomato_egg
        return decodeResourceSafely(context, id)
    }

    private fun decodeDownsampled(context: Context, uriString: String): Bitmap? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return try {
            // 第一趟只读尺寸，据此决定采样率，避免把几千万像素的原图整个读进内存
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, TARGET_DECODE_WIDTH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeResourceSafely(context: Context, id: Int): Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, id)
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

    /** 只按宽度降采样：分享图宽度固定，高度再多也无所谓（会被居中裁切显示） */
    private fun calculateSampleSize(sourceWidth: Int, targetWidth: Int): Int {
        var sample = 1
        var width = sourceWidth
        while (width / 2 >= targetWidth && sample < 8) {
            width /= 2
            sample *= 2
        }
        return sample
    }
}
