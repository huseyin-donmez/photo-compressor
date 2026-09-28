import Foundation
import ImageResizerCore

/// UserDefaults-backed persistence for the pure `UsageState`
/// (core `Entitlement` owns every rule; this only remembers the answer).
final class Store: ObservableObject {
    private let defaults = UserDefaults.standard
    private let creditsKey = "usage.remainingCredits"
    private let premiumKey = "usage.isPremium"

    @Published private(set) var state: UsageState

    init() {
        let premium = defaults.bool(forKey: premiumKey)
        let credits = defaults.object(forKey: creditsKey) == nil
            ? ProductRules.initialFreeCredits
            : defaults.integer(forKey: creditsKey)
        state = UsageState(remainingFreeCredits: max(0, credits), isPremium: premium)
    }

    /// Call exactly once per successfully saved, re-encoded image.
    func consumeCredit() {
        var s = state
        Entitlement.consumeCredit(state: &s)
        persist(s)
    }

    /// Reward callback from the ad SDK (+5; never for premium).
    func grantRewarded() {
        var s = state
        Entitlement.grantRewardedAd(state: &s)
        persist(s)
    }

    func setPremium(_ premium: Bool) {
        guard premium != state.isPremium else { return }
        var s = state
        s.isPremium = premium
        persist(s)
    }

    private func persist(_ s: UsageState) {
        state = s
        defaults.set(s.remainingFreeCredits, forKey: creditsKey)
        defaults.set(s.isPremium, forKey: premiumKey)
    }
}
