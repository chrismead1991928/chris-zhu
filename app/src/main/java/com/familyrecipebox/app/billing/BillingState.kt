package com.familyrecipebox.app.billing

import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase

/**
 * 计费状态数据类。
 *
 * @param isPremium 用户是否已解锁高级版。
 *   这是一次性买断，不是订阅，因此不存在到期、续费或宽限期，
 *   只取决于「是否持有一笔有效且已确认的一次性购买」。
 * @param productDetails 从 Google Play 查询到的商品详情（用于取价格）
 * @param purchases 当前设备上已确认的购买记录
 * @param errorMessage billing 过程中出现的错误信息
 */
data class BillingState(
    val isPremium: Boolean = false,
    val productDetails: List<ProductDetails> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    val errorMessage: String? = null
)
