package com.imageresizer.app.ads

import android.app.Activity

/**
 * Seam (docs/ALGORITHM.md): UI depends only on this interface so the AdMob
 * implementation can be swapped (or faked in tests) without touching product code.
 */
interface AdServing {
    /**
     * Consent (UMP) + warm load. Called once with an Activity (consent flows
     * need one). Best effort: failures never throw, ads just may not serve.
     */
    fun prepare(activity: Activity)

    /**
     * Show a rewarded ad.
     * @return true only if the reward callback actually fired.
     */
    suspend fun showRewarded(activity: Activity): Boolean
}
