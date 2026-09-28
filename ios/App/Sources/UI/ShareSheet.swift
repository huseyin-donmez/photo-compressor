import SwiftUI
import UIKit

/// Share one or many output files — the iOS twin of Android's share-out
/// chooser (iOS has no cross-app share sheet target like SEND, so this
/// wraps UIActivityViewController).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(
        _ controller: UIActivityViewController, context: Context
    ) {}
}
