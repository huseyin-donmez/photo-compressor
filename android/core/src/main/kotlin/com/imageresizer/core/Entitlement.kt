package com.imageresizer.core

import kotlin.math.max

/** Free/Premium/rewarded-ad logic. Pure and unit-tested; persistence lives in `Store`. */
data class UsageState(
    var remainingFreeCredits: Int = ProductRules.initialFreeCredits,
    var isPremium: Boolean = false,
) {
    init {
        remainingFreeCredits = max(0, remainingFreeCredits)
    }
}

/** Preflight decision for a batch, computed BEFORE any image is processed. */
sealed class BatchPlan {
    /** Premium: process everything. */
    object Unlimited : BatchPlan()

    /** Every item (including all items needing work) can run. */
    data class Allowed(val neededWork: Int) : BatchPlan()

    /**
     * Run items in order until credits run out on an item that needs work,
     * then surface the limit screen (rewarded ad / purchase).
     */
    data class Partial(val allowedWork: Int) : BatchPlan()

    /** Work is needed but no credits remain: show limit screen before any processing. */
    object Blocked : BatchPlan()
}

object Entitlement {
    /**
     * 1 credit = 1 image that (a) needed re-encoding (input > target) and
     * (b) was successfully saved. Pass-through and failures are free.
     */
    fun plan(neededWork: Int, state: UsageState): BatchPlan {
        require(neededWork >= 0) { "neededWork must be ≥ 0" }
        if (state.isPremium) return BatchPlan.Unlimited
        if (neededWork == 0) return BatchPlan.Allowed(0)
        val remaining = state.remainingFreeCredits
        if (remaining <= 0) return BatchPlan.Blocked
        if (remaining >= neededWork) return BatchPlan.Allowed(neededWork)
        return BatchPlan.Partial(remaining)
    }

    /**
     * Call exactly once per successfully saved, re-encoded image — immediately
     * before temp cleanup, so a crash can never grant free work.
     */
    fun consumeCredit(state: UsageState) {
        if (state.isPremium) return
        state.remainingFreeCredits = max(0, state.remainingFreeCredits - 1)
    }

    /**
     * Reward callback from the ad SDK. Never granted for premium users
     * (ads are never even loaded for them).
     */
    fun grantRewardedAd(state: UsageState, count: Int = ProductRules.rewardedAdGrantCount) {
        if (state.isPremium || count <= 0) return
        state.remainingFreeCredits += count
    }
}
