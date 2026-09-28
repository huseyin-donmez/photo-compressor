package com.imageresizer.app.billing

import android.app.Activity

/**
 * Seam (docs/ALGORITHM.md): one-off lifetime unlock + restore, local entitlement
 * check only — no backend, no subscription.
 */
interface BillingService {
    fun setListener(listener: Listener?)

    /** Open the connection and run the initial entitlement query. */
    fun connect()

    /** Re-run the local entitlement query (also serves as "Restore purchases"). */
    fun queryEntitlement()

    fun purchase(activity: Activity)

    interface Listener {
        /** Play Billing is the source of truth for the premium flag. */
        fun onEntitlementChanged(isPremium: Boolean)

        /** Connection/product failures: UI shows a message, nothing crashes. */
        fun onBillingUnavailable(message: String)
    }
}
