import SwiftUI
import UIKit

/// Horizontal strip of selection thumbnails with tap-to-remove — the iOS
/// twin of Android's `ThumbnailRow` (sampled decode, no full-res loads).
struct ThumbnailStrip: View {
    let images: [PickedImage]
    let onRemove: (PickedImage) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(images) { image in
                    ThumbnailView(image: image, onRemove: { onRemove(image) })
                }
            }
        }
    }
}

private struct ThumbnailView: View {
    let image: PickedImage
    let onRemove: () -> Void
    @State private var thumbnail: UIImage?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if let thumbnail {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                } else {
                    Rectangle().fill(Color(.secondarySystemBackground))
                }
            }
            .frame(width: 96, height: 96)
            .clipShape(RoundedRectangle(cornerRadius: 10))

            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .black.opacity(0.55))
                    .font(.system(size: 20))
            }
            .padding(4)
            .accessibilityLabel("Remove photo")
        }
        .task(id: image.url) {
            thumbnail = await Task.detached {
                Self.loadThumbnail(url: image.url)
            }.value
        }
    }

    /// Bounds-decoded 240px thumbnail (CGImageSource never materializes the
    /// full-resolution image — same approach as Android's sampled decode).
    private static func loadThumbnail(url: URL) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 240,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(
            source, 0, options as CFDictionary
        ) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
