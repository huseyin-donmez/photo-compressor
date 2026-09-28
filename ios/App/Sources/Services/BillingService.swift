import Foundation
import StoreKit

/// StoreKit 2 lifetime unlock — the iOS twin of Android's PlayBilling.
/// Product id matches `PRODUCT_LIFETIME` ("lifetime"); `Products.storekit`
/// enables local testing before App Store Connect exists.
@MainActor
final class BillingService: ObservableObject {
    static let lifetimeProductID = "lifetime"

    @Published private(set) var isPremium = false
    @Published private(set) var priceLabel = "$5"

    private var updatesTask: Task<Void, Never>?

    func start() {
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                await self?.finishAndRefresh(verification: update)
            }
        }
        Task {
            await loadPrice()
            await refresh()
        }
    }

    deinit {
        updatesTask?.cancel()
    }

    /// Entitlement = a verified, non-revoked `lifetime` transaction.
    func refresh() async {
        var premium = false
        for await entry in Transaction.currentEntitlements {
            if case .verified(let transaction) = entry,
               transaction.productID == Self.lifetimeProductID,
               transaction.revocationDate == nil {
                premium = true
            }
        }
        isPremium = premium
    }

    func purchase() async {
        guard let product = try? await Product.products(for: [Self.lifetimeProductID]).first
        else { return }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await transaction.finish()
                }
                await refresh()
            case .pending, .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            // Purchase errors fall back to the caller's notice flow.
        }
    }

    /// Restore = resync with the App Store account (StoreKit 2 has no
    /// separate "restore" step; sync() triggers the account query).
    func restore() async {
        try? await AppStore.sync()
        await refresh()
    }

    private func loadPrice() async {
        guard let product = try? await Product.products(for: [Self.lifetimeProductID]).first
        else { return }
        priceLabel = product.displayPrice
    }

    private func finishAndRefresh(verification: VerificationResult<Transaction>) async {
        if case .verified(let transaction) = verification {
            await transaction.finish()
        }
        await refresh()
    }
}
