import GoogleMobileAds
import SwiftUI
import UIKit

/// Anchored adaptive banner for free users (premium removes it — the $5
/// promise). GMA's Swift class name is `BannerView`, so this wrapper is
/// `AdBannerView` to avoid the collision. Height is computed exactly from
/// the ad size so no empty strip appears under a shorter ad.
struct AdBannerView: UIViewRepresentable {
    let width: CGFloat

    /// Full-height of the anchored adaptive banner for a given width (50–150dp).
    static func adaptiveHeight(width: CGFloat) -> CGFloat {
        largeAnchoredAdaptiveBanner(width: width).size.height
    }

    func makeUIView(context: Context) -> BannerView {
        let adSize = largeAnchoredAdaptiveBanner(width: width)
        let banner = BannerView(adSize: adSize)
        banner.adUnitID = AdService.bannerUnitID
        banner.rootViewController = AdService.sharedTopViewController()
        banner.load(Request())
        return banner
    }

    func updateUIView(_ banner: BannerView, context: Context) {}
}
