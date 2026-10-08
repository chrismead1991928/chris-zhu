package com.familyrecipebox.app.ui.premium

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.familyrecipebox.app.R
import com.familyrecipebox.app.billing.BillingManager
import com.familyrecipebox.app.billing.FreeTierLimiter

/**
 * 用户主动查看高级版时展示的弹窗（设置页入口）。
 *
 * 与 [RecipeLimitDialog] 的分工：这个不解释任何限制，
 * 因为用户本来就是自己点进来看的。
 */
@Composable
fun PremiumDialog(
    billingManager: BillingManager,
    onDismiss: () -> Unit
) {
    val state by billingManager.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.premium_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state.isPremium) {
                    PremiumUnlockedContent()
                } else {
                    FreeTierContent()
                    Spacer(modifier = Modifier.height(16.dp))
                    UnlockOption(
                        productDetails = state.productDetails.firstOrNull(),
                        onUnlock = { product ->
                            activity?.let { billingManager.launchBillingFlow(it, product) }
                        }
                    )
                    BillingError(state.errorMessage)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

/**
 * 撞到免费版菜谱上限时展示的说明型弹窗。
 *
 * 与 [PremiumDialog] 的关键区别：这里必须**先讲清为什么弹出来**，再给购买入口。
 *
 * 去掉解释会有实际代价：用户点「+」加第 21 道菜，却迎面看到价格，
 * 观感更接近随机广告而不是说明，而这是这类上限最主要的差评来源。
 * 因此顺序固定为「说明处境 → 安抚已有数据 → 给出方案」。
 *
 * @param limit 当前生效的免费版上限，由调用方传入以保持与判定逻辑同源
 */
@Composable
fun RecipeLimitDialog(
    billingManager: BillingManager,
    limit: Int,
    onDismiss: () -> Unit
) {
    val state by billingManager.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.free_limit_recipes_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.free_limit_recipes_message, limit),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.premium_upgrade_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.premium_upgrade_message),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))
                UnlockOption(
                    productDetails = state.productDetails.firstOrNull(),
                    onUnlock = { product ->
                        activity?.let { billingManager.launchBillingFlow(it, product) }
                    }
                )
                BillingError(state.errorMessage)
            }
        },
        confirmButton = {
            // 只放「以后再说」，不额外给一个更显眼的购买按钮：
            // 用户是被动撞到上限的，此刻逼单的收益远低于好感损失
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.not_now))
            }
        }
    )
}

@Composable
private fun BillingError(message: String?) {
    message ?: return
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun PremiumUnlockedContent() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.premium_unlocked))
    }
}

@Composable
private fun FreeTierContent() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.premium_free_tier_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            // 上限值来自 FreeTierLimiter 常量，避免文案里的数字与代码里的限制各说各话
            text = stringResource(
                R.string.premium_free_tier_desc,
                FreeTierLimiter.FREE_RECIPE_LIMIT
            ),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 单一买断入口。
 *
 * 与订阅版本的区别：没有「选择方案」和多个价格按钮。
 * 一次性商品只有一个价格，多出来的选择只会增加犹豫。
 *
 * 价格尚未查询到时禁用按钮，避免用户点进一个拿不到价格的购买页。
 */
@Composable
private fun UnlockOption(
    productDetails: ProductDetails?,
    onUnlock: (ProductDetails) -> Unit
) {
    // 三个状态互斥：还没查到商品 / 查到了但价格字段为空 / 有价格。
    // 用 priceLabel 一次算清，避免在 Button 里嵌按钮来表达价格
    val priceLabel = when (productDetails) {
        null -> stringResource(R.string.premium_loading_price)
        else -> readOneTimePrice(productDetails)
            ?: stringResource(R.string.premium_price_unknown)
    }

    OutlinedButton(
        onClick = { productDetails?.let(onUnlock) },
        enabled = productDetails != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.premium_unlock),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = stringResource(R.string.premium_one_time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = priceLabel,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * 读取一次性商品的价格文案。
 *
 * 订阅价格在 `subscriptionOfferDetails` 里，一次性商品在 `oneTimePurchaseOfferDetails` 里，
 * 取错字段会得到 null，界面上就会一直显示占位符。
 */
private fun readOneTimePrice(details: ProductDetails): String? =
    details.oneTimePurchaseOfferDetails?.formattedPrice
