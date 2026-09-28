import Foundation
import SwiftUI
import UIKit

/// A photo in the current selection. `assetID` (PhotosPicker item id) is the
/// dedup key — same rule as Android's URI dedup across picking waves.
struct PickedImage: Identifiable, Hashable {
    let id: UUID
    let assetID: String?
    let url: URL
    let name: String

    init(assetID: String?, url: URL, name: String) {
        self.id = UUID()
        self.assetID = assetID
        self.url = url
        self.name = name
    }
}

/// Root UI: one screen for pick/target/start, one for the batch run.
/// All long-lived state (credits, batch, billing) lives here — matching
/// Android's AppRoot.
struct AppRoot: View {
    @StateObject private var store: Store
    @StateObject private var processor: BatchProcessor
    @StateObject private var billing = BillingService()
    @StateObject private var ads = AdService()

    @AppStorage("target.unit") private var storedUnit = SizeUnit.defaultUnit.rawValue
    @AppStorage("target.value") private var storedValue = TargetSizes.defaultValue

    @State private var selection: [PickedImage] = []
    @State private var showLimit = false
    @State private var notice: String?

    init() {
        let store = Store()
        _store = StateObject(wrappedValue: store)
        _processor = StateObject(wrappedValue: BatchProcessor(store: store))
    }

    private var unit: SizeUnit { SizeUnit(rawValue: storedUnit) ?? .mb }
    private var targetBytes: Int64 { TargetSizes.bytes(unit: unit, value: storedValue) }

    var body: some View {
        Group {
            if let batch = processor.state, !batch.items.isEmpty {
                BatchView(
                    state: batch,
                    photoSaveFailed: processor.photoSaveFailed,
                    onDone: { processor.clear() },
                    onResume: { processor.resume() },
                    onCancel: { processor.cancelBatch() },
                    onGetCredits: { showLimit = true },
                )
            } else {
                HomeView(
                    unit: unit,
                    value: storedValue,
                    selection: selection,
                    usage: store.state,
                    priceLabel: billing.priceLabel,
                    onTargetChange: { newUnit, newValue in
                        storedUnit = newUnit.rawValue
                        storedValue = newValue
                    },
                    onPicked: { picked in
                        let known = Set(selection.compactMap(\.assetID))
                        let fresh = picked.filter { image in
                            guard let assetID = image.assetID else { return true }
                            return !known.contains(assetID)
                        }
                        selection.append(contentsOf: fresh)
                    },
                    onClearSelection: { selection = [] },
                    onRemovePhoto: { image in selection.removeAll { $0.id == image.id } },
                    onResize: {
                        guard !selection.isEmpty else { return }
                        let urls = selection.map(\.url)
                        let bytes = targetBytes
                        Task {
                            // One system prompt, before the first save attempt.
                            _ = await PhotoLibraryWriter.requestAddPermission()
                            processor.start(sources: urls, targetBytes: bytes)
                        }
                    },
                    onWatchAd: watchAd,
                    onUnlock: {
                        Task { await billing.purchase() }
                    },
                    onRestore: {
                        Task {
                            await billing.restore()
                            if !billing.isPremium { notice = "No purchases found" }
                        }
                    },
                )
            }
        }
        // Free-tier banner (the lifetime unlock removes it). The app is
        // portrait-only, so the screen width is stable for the ad size.
        .safeAreaInset(edge: .bottom) {
            if !store.state.isPremium {
                AdBannerView(width: UIScreen.main.bounds.width)
                    .frame(height: AdBannerView.adaptiveHeight(width: UIScreen.main.bounds.width))
            }
        }
        .onAppear {
            ads.prepare()
            billing.start()
        }
        .onChange(of: billing.isPremium) { premium in
            if premium && !store.state.isPremium {
                store.setPremium(true)
                processor.resume()
                notice = "Lifetime unlocked — ads removed"
            } else if !premium && store.state.isPremium {
                store.setPremium(false)
            }
        }
        .alert("Out of credits", isPresented: $showLimit) {
            Button("Watch ad +5") { watchAd() }
            Button("Unlock \(billing.priceLabel)") {
                Task { await billing.purchase() }
            }
            Button("Restore") {
                Task {
                    await billing.restore()
                    if !billing.isPremium { notice = "No purchases found" }
                }
            }
            Button("Not now", role: .cancel) {}
        } message: {
            Text(
                "You have \(store.state.remainingFreeCredits) credits left. " +
                "Watch an ad for 5 more, or unlock lifetime to remove limits and ads."
            )
        }
        .alert(
            notice ?? "",
            isPresented: Binding(
                get: { notice != nil },
                set: { if !$0 { notice = nil } }
            )
        ) {
            Button("OK") { notice = nil }
        }
    }

    private func watchAd() {
        guard !store.state.isPremium else { return }
        Task {
            let granted = await ads.showRewarded()
            if granted {
                store.grantRewarded()
                processor.resume()
                notice = "+5 credits added"
            } else {
                notice = "Ad not available right now — try again"
            }
        }
    }
}
