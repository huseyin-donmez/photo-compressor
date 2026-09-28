package com.imageresizer.app.store

import android.content.Context
import com.imageresizer.core.Entitlement
import com.imageresizer.core.ProductRules
import com.imageresizer.core.UsageState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SharedPreferences-backed persistence for the free-tier state.
 * Pure logic lives in `core`'s [Entitlement]; this class only stores + emits it.
 *
 * Note: mutations are always published as a NEW [UsageState] instance — StateFlow
 * cannot see in-place `var` changes on the held instance, so the UI would go stale.
 */
class UsageStore(context: Context) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UsageState(
            remainingFreeCredits = prefs.getInt(KEY_CREDITS, ProductRules.initialFreeCredits),
            isPremium = prefs.getBoolean(KEY_PREMIUM, false),
        ),
    )
    val state: StateFlow<UsageState> = _state

    val current: UsageState get() = _state.value

    /** Call exactly once per successfully saved, re-encoded image. */
    fun consumeCredit() {
        val next = _state.value.copy()
        Entitlement.consumeCredit(next)
        if (next == _state.value) return
        _state.value = next
        save()
    }

    /** Reward callback from the ad SDK (never for premium users). */
    fun grantRewardedAd() {
        val next = _state.value.copy()
        Entitlement.grantRewardedAd(next)
        if (next == _state.value) return
        _state.value = next
        save()
    }

    /** Local entitlement check result from Play Billing (queryPurchases = source of truth). */
    fun setPremium(isPremium: Boolean) {
        val next = _state.value.copy(isPremium = isPremium)
        if (next == _state.value) return
        _state.value = next
        save()
    }

    private fun save() {
        prefs.edit()
            .putInt(KEY_CREDITS, _state.value.remainingFreeCredits)
            .putBoolean(KEY_PREMIUM, _state.value.isPremium)
            .apply()
    }

    private companion object {
        const val KEY_CREDITS = "credits"
        const val KEY_PREMIUM = "premium"
    }
}
