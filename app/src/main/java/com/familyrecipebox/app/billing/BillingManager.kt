package com.familyrecipebox.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Google Play Billing 管理器。
 *
 * 商品模型是**一次性买断**（一次性商品，ProductType.INAPP），不是订阅：
 * - 商品 ID：`premium_unlock`，买断后永久解锁无限菜谱
 * - 没有周期、没有续费、没有宽限期，因此不涉及订阅状态机
 *
 * 两个与订阅实现的关键差异，改动时容易踩：
 *
 * 1. **不能用 `isAutoRenewing` 判断权益。** 一次性购买的该字段恒为 false，
 *    沿用订阅的判断方式会导致「付了钱却始终解锁不了」。
 *    正确依据是 purchaseState == PURCHASED。
 * 2. **确认（acknowledge）是强制的。** 一次性商品若 3 天内未确认，
 *    Google Play 会自动退款给用户。这里在收到购买回调与查询已购时都会确认。
 *
 * 恢复购买无需额外入口：重装或换机后 `queryPurchases()` 会拉回这笔已购记录。
 */
class BillingManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        handlePurchases(billingResult, purchases)
    }

    private val billingClient: BillingClient? = try {
        BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .build()
    } catch (e: Exception) {
        // 购买是可选能力：设备缺少 Google Play 服务时不应影响应用启动
        Log.e(TAG, "初始化 Billing 客户端失败，购买功能将不可用", e)
        _state.update { it.copy(errorMessage = e.message) }
        null
    }

    private val productIds = listOf(PREMIUM_UNLOCK)

    private val acknowledgeCache = mutableSetOf<String>()

    init {
        startConnection()
    }

    fun startConnection() {
        // 购买只是可选能力：设备缺少 Google Play 服务时不应影响应用启动
        val client = billingClient
        if (client == null) {
            Log.w(TAG, "Billing 客户端不可用，跳过连接")
            return
        }

        try {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProductDetails()
                        queryPurchases()
                    } else {
                        _state.update { it.copy(errorMessage = formatError(result)) }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // 可在这里重连；为简单起见暂不自动重连
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Billing 连接失败", e)
            _state.update { it.copy(errorMessage = e.message) }
        }
    }

    /**
     * 查询商品详情，用于展示价格。
     */
    private fun queryProductDetails() {
        val client = billingClient ?: return

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()

        scope.launch {
            try {
                val result = client.queryProductDetails(params)
                if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _state.update { current ->
                        current.copy(
                            productDetails = result.productDetailsList ?: emptyList(),
                            errorMessage = null
                        )
                    }
                } else {
                    _state.update { it.copy(errorMessage = formatError(result.billingResult)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    /**
     * 查询用户已购买的一次性商品。
     *
     * 这也是「恢复购买」的唯一路径：重装或换机后，这笔已确认的购买会被拉回来，
     * 因此不需要单独的恢复按钮。
     */
    fun queryPurchases() {
        // 购买只是可选能力：缺少 Google Play 服务时客户端为 null，直接跳过
        val client = billingClient ?: return
        if (!client.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        try {
            client.queryPurchasesAsync(params) { result, purchases ->
                handlePurchases(result, purchases)
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询已购商品失败", e)
        }
    }

    /**
     * 启动买断购买流程。
     *
     * 一次性商品没有 offerToken（那是订阅基础方案才有的东西），
     * 因此这里刻意不调用 setOfferToken——对 INAPP 调用它会抛异常。
     */
    fun launchBillingFlow(activity: Activity, productDetails: ProductDetails) {
        val client = billingClient
        if (client == null) {
            _state.update { it.copy(errorMessage = UNAVAILABLE_MESSAGE) }
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        try {
            val result = client.launchBillingFlow(activity, flowParams)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _state.update { it.copy(errorMessage = formatError(result)) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动购买流程失败", e)
            _state.update { it.copy(errorMessage = e.message) }
        }
    }

    /**
     * 处理购买结果，确认购买并更新状态。
     */
    private fun handlePurchases(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // 只认已完成的购买：PENDING（如待处理的银行转账）不能解锁，
                // 否则用户可能不付款就拿到权益
                val validPurchases = purchases
                    ?.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    ?: emptyList()

                validPurchases.forEach { purchase ->
                    acknowledgePurchaseIfNeeded(purchase)
                }

                // 一次性买断的权益只看「有没有已完成的购买」，
                // 不能看 isAutoRenewing——它对 INAPP 恒为 false
                val isPremium = validPurchases.isNotEmpty()

                // 落盘一份状态：后台任务启动时 Billing 可能尚未连接完成，
                // 只能依赖这个最近已知值
                persistPremium(isPremium)

                _state.update { current ->
                    current.copy(
                        isPremium = isPremium,
                        purchases = validPurchases,
                        errorMessage = null
                    )
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // 用户取消，不更新状态
            }
            else -> {
                _state.update { it.copy(errorMessage = formatError(result)) }
            }
        }
    }

    private fun persistPremium(isPremium: Boolean) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_PREMIUM, isPremium)
            .apply()
    }

    /**
     * 确认购买。
     *
     * 对一次性商品，这一步不是可选的：3 天内未确认会被自动退款，
     * 用户会先拿到权益再被收回，观感极差。
     */
    private fun acknowledgePurchaseIfNeeded(purchase: Purchase) {
        val client = billingClient ?: return
        if (purchase.isAcknowledged || acknowledgeCache.contains(purchase.purchaseToken)) return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        try {
            client.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    acknowledgeCache.add(purchase.purchaseToken)
                } else {
                    Log.e(TAG, "确认购买失败: ${result.responseCode} ${result.debugMessage}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "确认购买失败", e)
        }
    }

    /**
     * Billing 客户端是否可用（设备是否具备 Google Play 服务）
     */
    fun isAvailable(): Boolean = billingClient != null

    private fun formatError(result: BillingResult): String? {
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            null
        } else {
            "Billing error ${result.responseCode}: ${result.debugMessage}"
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    companion object {
        /** 一次性买断商品 ID，需在 Play Console 中创建为「一次性商品」 */
        const val PREMIUM_UNLOCK = "premium_unlock"

        private const val TAG = "BillingManager"
        private const val PREFS_NAME = "billing_state"
        private const val KEY_IS_PREMIUM = "is_premium"

        /** 设备缺少 Google Play 服务时的统一提示 */
        private const val UNAVAILABLE_MESSAGE = "Google Play services unavailable"

        @Volatile
        private var INSTANCE: BillingManager? = null

        fun getInstance(context: Context): BillingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * 读取最近一次已知的高级版状态。
         *
         * 供后台任务使用：WorkManager 拉起进程后 Billing 客户端尚未连接完成，
         * 此时 `state.isPremium` 仍是默认的 false，直接采用会误判为未解锁。
         * 返回值为 false 只代表「没有已知的已解锁记录」，不代表一定未购买。
         */
        fun lastKnownPremium(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_PREMIUM, false)
        }
    }
}
