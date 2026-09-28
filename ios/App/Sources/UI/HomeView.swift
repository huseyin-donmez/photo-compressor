import CoreTransferable
import ImageResizerCore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Home: header, size wheel, selection strip, resize — mirrored 1:1 from
/// Android's HomeScreen (banner arrives from AppRoot's bottom inset).
struct HomeView: View {
    let unit: SizeUnit
    let value: Int
    let selection: [PickedImage]
    let usage: UsageState
    let priceLabel: String
    let onTargetChange: (SizeUnit, Int) -> Void
    let onPicked: ([PickedImage]) -> Void
    let onClearSelection: () -> Unit
    let onRemovePhoto: (PickedImage) -> Void
    let onResize: () -> Void
    let onWatchAd: () -> Void
    let onUnlock: () -> Void
    let onRestore: () -> Void

    @State private var pickerItems: [PhotosPickerItem] = []

    private var targetBytes: Int64 {
        TargetSizes.bytes(unit: unit, value: value)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 10) {
                    Image("LogoMark")
                        .renderingMode(.template)
                        .resizable()
                        .foregroundColor(.accentColor)
                        .frame(width: 34, height: 34)
                    Text("Photo Compressor")
                        .font(.title)
                }
                Text(
                    "Compress photos under a size limit. Everything runs on " +
                    "your device — photos are never uploaded."
                )
                .font(.callout)
                .foregroundColor(.secondary)

                SizePicker(unit: unit, value: value, onChange: onTargetChange)
                Text("Output will be at most \(formatBytes(targetBytes))")
                    .font(.caption)
                    .foregroundColor(.secondary)

                HStack(spacing: 8) {
                    Text(
                        selection.isEmpty
                            ? "No photos selected"
                            : "\(selection.count) selected"
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if !selection.isEmpty {
                        Button("Clear", action: onClearSelection)
                            .buttonStyle(.borderless)
                    }
                    PhotosPicker(
                        selection: $pickerItems,
                        maxSelectionCount: 500,
                        selectionBehavior: .ordered,
                        matching: .images
                    ) {
                        Text("Choose photos")
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(
                                Capsule().strokeBorder(Color.accentColor)
                            )
                            .foregroundColor(.accentColor)
                    }
                }

                if !selection.isEmpty {
                    ThumbnailStrip(images: selection, onRemove: onRemovePhoto)
                }

                Button(action: onResize) {
                    Text(
                        selection.isEmpty
                            ? "Pick photos first"
                            : "Resize \(selection.count) photos"
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(selection.isEmpty)

                Spacer(minLength: 8)

                if usage.isPremium {
                    Text("Lifetime unlocked — ads removed")
                        .foregroundColor(.accentColor)
                } else {
                    HStack(spacing: 8) {
                        Text("Credits: \(usage.remainingFreeCredits)")
                            .fontWeight(.semibold)
                        Button("Watch ad +5", action: onWatchAd)
                            .buttonStyle(.borderless)
                    }
                    HStack(spacing: 8) {
                        Button("Unlock lifetime — \(priceLabel)", action: onUnlock)
                            .buttonStyle(.borderedProminent)
                        Button("Restore", action: onRestore)
                            .buttonStyle(.borderless)
                    }
                }
                Text(
                    "1 credit is used per photo that actually needs " +
                    "re-encoding. Photos already under the limit are always free."
                )
                .font(.caption)
                .foregroundColor(.secondary)
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onChange(of: pickerItems) { items in
            guard !items.isEmpty else { return }
            Task {
                var picked: [PickedImage] = []
                for item in items {
                    if let file = try? await item.loadTransferable(type: PickedImageFile.self) {
                        picked.append(
                            PickedImage(
                                assetID: item.itemIdentifier,
                                url: file.url,
                                name: file.name
                            )
                        )
                    }
                }
                onPicked(picked)
                // Reset so the same photos can be chosen again in a later wave.
                pickerItems = []
            }
        }
    }
}

/// Imports a picked photo to a temp file while keeping the original filename
/// (display name only — format sniffing is header-based in the core).
private struct PickedImageFile: Transferable {
    let url: URL
    let name: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            let dir = FileManager.default.temporaryDirectory
                .appendingPathComponent("inputs", isDirectory: true)
            try FileManager.default.createDirectory(
                at: dir, withIntermediateDirectories: true
            )
            let name = received.file.lastPathComponent
            let destination = dir.appendingPathComponent(
                "\(UUID().uuidString)_\(name)"
            )
            try FileManager.default.copyItem(at: received.file, to: destination)
            return PickedImageFile(url: destination, name: name)
        }
    }
}
