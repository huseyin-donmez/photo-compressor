import Foundation
import GoogleMobileAds
import UIKit
// UMP ships as a binary module named UserMessagingPlatform; its Swift names
// are ConsentInformation / ConsentForm (verified against headers 13.10 / 3.1).
import UserMessagingPlatform

/// AdMob: rewarded ads + UMP consent + banner ids. Contextual targeting only
/// (no ATT prompt — same decision as Android, docs/ALGORITHM.md).
/// Google's PUBLIC TEST unit ids while developing — swap before publishing.
///
/// Isolation notes: all mutable state is behind `lock`; anything UIKit/UMP
/// touches is explicitly hop-to-main via DispatchQueue.main, keeping the type
/// free of actor annotations (headers mark the GMA/UMP APIs NS_SWIFT_UI_ACTOR).
final class AdService: NSObject, @unchecked Sendable {
    static let rewardedUnitID = "ca-app-pub-3940256099942544/5224354917"
    static let bannerUnitID = "ca-app-pub-3940256099942544/6300978111"

    private let lock = NSLock()
    private var rewarded: RewardedAd?
    private var loading = false
    private var presentation: RewardedPresentation?
    private var consentChecked = false

    /// SDK init → consent (form where required) → warm the first rewarded ad.
    /// Everything is best-effort: failures just mean ads may not serve.
    func prepare() {
        MobileAds.shared().start { [weak self] in
            // UMP requires the main thread for every call.
            DispatchQueue.main.async { self?.ensureConsentThenWarm() }
        }
    }

    /// Main-thread only (callers dispatch).
    private func ensureConsentThenWarm() {
        if !consentChecked {
            consentChecked = true
            ConsentInformation.shared.requestConsentInfoUpdate(with: nil) { _ in
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    if let vc = Self.topViewController() {
                        ConsentForm.loadAndPresentIfRequired(from: vc) { _ in self.warm() }
                    } else {
                        self.warm()
                    }
                }
            }
            return
        }
        warm()
    }

    private func warm() {
        lock.lock()
        let shouldLoad = rewarded == nil && !loading
        if shouldLoad { loading = true }
        lock.unlock()
        guard shouldLoad else { return }
        RewardedAd.load(with: Self.rewardedUnitID, request: Request()) { [weak self] ad, _ in
            guard let self else { return }
            self.lock.lock()
            self.loading = false
            if let ad { self.rewarded = ad }
            self.lock.unlock()
        }
    }

    /// Shows a rewarded ad. Returns true only if the reward callback fired
    /// (same contract as Android's AdServing.showRewarded).
    func showRewarded() async -> Bool {
        warm()
        lock.lock()
        let ad = rewarded
        rewarded = nil
        lock.unlock()
        guard let ad else { return false }
        return await withCheckedContinuation { continuation in
            let shown = RewardedPresentation(continuation: continuation, owner: self)
            lock.lock()
            presentation = shown
            lock.unlock()
            ad.fullScreenContentDelegate = shown
            // present(from:) is main-actor (NS_SWIFT_UI_ACTOR) — hop to main.
            DispatchQueue.main.async {
                guard let vc = Self.topViewController() else {
                    shown.resume(with: false)
                    self.adClosed()
                    return
                }
                ad.present(from: vc) {
                    shown.markEarned()
                }
            }
        }
    }

    /// Called (on main) after a presentation ends — refill the cache.
    fileprivate func adClosed() {
        lock.lock()
        presentation = nil
        lock.unlock()
        warm()
    }

    /// Exposed for AdBannerView (makeUIView runs on main).
    static func sharedTopViewController() -> UIViewController? { topViewController() }

    /// Main-thread only (UIKit); callers hop to main first.
    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap { $0.windows }
        let key = windows.first(where: \.isKeyWindow) ?? windows.first
        var top = key?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

/// Bridges the full-screen callbacks into one resumable continuation
/// (resume-once). The reward handler runs on main alongside the delegate
/// callbacks, so the flags are plain state.
private final class RewardedPresentation: NSObject, FullScreenContentDelegate {
    private var continuation: CheckedContinuation<Bool, Never>?
    private var earned = false
    private weak var owner: AdService?

    init(continuation: CheckedContinuation<Bool, Never>, owner: AdService) {
        self.continuation = continuation
        self.owner = owner
    }

    func markEarned() {
        earned = true
    }

    func resume(with result: Bool) {
        continuation?.resume(returning: result)
        continuation = nil
    }

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        resume(with: earned)
        owner?.adClosed()
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        resume(with: false)
        owner?.adClosed()
    }
}
