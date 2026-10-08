package com.familyrecipebox.app.ui.edit

/**
 * 菜谱插画生成提示词技能（Image Prompt Skill）。
 *
 * 用于指导其他 AI 生成与当前应用风格一致的菜谱卡片插画：
 * - 暖色调、卡通手绘风格、有食欲感
 * - 不使用纯白背景，统一使用暖米色背景
 * - 无文字、无水印、无边框
 */
object ImagePromptSkill {

    /** 参考背景色，暖米色/奶油色背景 */
    const val BACKGROUND_COLOR_HEX = "#FFF5E6"

    private const val STYLE_INSTRUCTIONS =
        "Style: warm and cute cartoon food illustration, hand-drawn feel, soft natural lighting, " +
        "rich appetizing colors, clean lines, slightly textured. " +
        "Background: warm cream/beige color ($BACKGROUND_COLOR_HEX), NOT pure white, " +
        "subtle paper texture, no text, no watermark, no border. " +
        "The dish should look delicious and home-cooked, with friendly and inviting mood."

    /**
     * 根据菜谱信息生成默认的图片生成提示词。
     *
     * @param title 菜谱名称
     * @param category 分类 key（Main/Side/Salad/Dessert）
     * @param categoryDisplayName 首页显示的分类名称，会自动同步到提示词中
     * @param steps 制作步骤，用于描述菜品呈现状态
     * @param tips 烹饪要点，用于补充视觉细节
     */
    fun buildPrompt(
        title: String,
        category: String,
        categoryDisplayName: String,
        steps: List<String>,
        tips: List<String>
    ): String {
        val dishName = title.ifBlank { "this dish" }
        val displayName = categoryDisplayName.ifBlank { category }
        val categoryDesc = when (category) {
            "Main" -> "a hearty Western-style main course"
            "Side" -> "a cozy Western-style side dish"
            "Salad" -> "a fresh Western-style salad"
            "Dessert" -> "a sweet Western-style dessert"
            // Legacy fallback
            "Meat" -> "a hearty Western-style main course"
            "Vegetable" -> "a cozy Western-style side dish"
            "Cold Dish" -> "a fresh Western-style salad"
            else -> "a delicious home-cooked dish"
        }

        val stepsText = steps
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" ") { "- $it" }
            .let { if (it.isNotBlank()) "Cooking steps for reference: $it" else "" }

        val tipsText = tips
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" ") { "- $it" }
            .let { if (it.isNotBlank()) "Key tips: $it" else "" }

        val detailText = listOf(stepsText, tipsText).filter { it.isNotBlank() }.joinToString(" ")

        return buildString {
            append("A warm and appetizing cartoon-style illustration of \"$dishName\", ")
            append("$categoryDesc in the \"$displayName\" category. ")
            if (detailText.isNotBlank()) {
                append("$detailText. ")
            }
            append("The dish is shown in a serving bowl/plate from a slightly top-down angle, ")
            append("with clear ingredients and a cozy homemade feel. ")
            append(STYLE_INSTRUCTIONS)
        }
    }
}
