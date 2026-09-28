import Foundation

/// Free/Premium/rewarded-ad logic. Pure and unit-tested; persistence lives in `Store`.
public struct UsageState: Sendable, Equatable {
    public var remainingFreeCredits: Int
    public var isPremium: Bool

    public init(remainingFreeCredits: Int = ProductRules.initialFreeCredits,
                isPremium: Bool = false) {
        self.remainingFreeCredits = max(0, remainingFreeCredits)
        self.isPremium = isPremium
    }
}

/// Preflight decision for a batch, computed BEFORE any image is processed.
public enum BatchPlan: Sendable, Equatable {
    /// Premium: process everything.
    case unlimited
    /// Every item (including all items needing work) can run.
    case allowed(neededWork: Int)
    /// Run items in order until credits run out on an item that needs work,
    /// then surface the limit screen (rewarded ad / purchase).
    case partial(allowedWork: Int)
    /// Work is needed but no credits remain: show limit screen before any processing.
    case blocked
}

public enum Entitlement {
    /// 1 credit = 1 image that (a) needed re-encoding (input > target) and
    /// (b) was successfully saved. Pass-through and failures are free.
    public static func plan(neededWork: Int, state: UsageState) -> BatchPlan {
        precondition(neededWork >= 0, "neededWork must be ≥ 0")
        if state.isPremium { return .unlimited }
        if neededWork == 0 { return .allowed(neededWork: 0) }
        let remaining = state.remainingFreeCredits
        if remaining <= 0 { return .blocked }
        if remaining >= neededWork { return .allowed(neededWork: neededWork) }
        return .partial(allowedWork: remaining)
    }

    /// Call exactly once per successfully saved, re-encoded image — immediately
    /// before temp cleanup, so a crash can never grant free work.
    public static func consumeCredit(state: inout UsageState) {
        guard !state.isPremium else { return }
        state.remainingFreeCredits = max(0, state.remainingFreeCredits - 1)
    }

    /// Reward callback from the ad SDK. Never granted for premium users
    /// (ads are never even loaded for them).
    public static func grantRewardedAd(state: inout UsageState,
                                       count: Int = ProductRules.rewardedAdGrantCount) {
        guard !state.isPremium, count > 0 else { return }
        state.remainingFreeCredits += count
    }
}
