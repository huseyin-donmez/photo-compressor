import Foundation
import Photos

enum PhotoLibraryError: Error {
    case albumUnavailable
}

/// Saves finished images into a dedicated "Photo Compressor" album so results
/// never mix with originals. Best-effort: if the user denies access, outputs
/// remain shareable from the batch screen (the processor surfaces a notice).
enum PhotoLibraryWriter {
    private static let albumTitle = "Photo Compressor"
    private static var cachedAlbumID: String?

    static func requestAddPermission() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return status == .authorized || status == .limited
    }

    static func save(fileURL: URL) async throws {
        let album = try await ensureAlbum()
        try await PHPhotoLibrary.performChanges {
            let creation = PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
            guard let albumRequest = PHAssetCollectionChangeRequest(for: album),
                  let asset = creation.placeholderForCreatedAsset else { return }
            albumRequest.addAssets([asset] as NSArray)
        }
    }

    private static func ensureAlbum() async throws -> PHAssetCollection {
        if let id = cachedAlbumID, let album = fetchAlbum(localID: id) {
            return album
        }
        if let album = fetchAlbums(title: albumTitle).firstObject {
            cachedAlbumID = album.localIdentifier
            return album
        }
        var newID: String?
        try await PHPhotoLibrary.performChanges {
            newID = PHAssetCollectionChangeRequest
                .creationRequestForAssetCollection(withTitle: albumTitle)
                .placeholderForCreatedAssetCollection?.localIdentifier
        }
        guard let id = newID, let album = fetchAlbum(localID: id) else {
            throw PhotoLibraryError.albumUnavailable
        }
        cachedAlbumID = id
        return album
    }

    private static func fetchAlbum(localID: String) -> PHAssetCollection? {
        PHAssetCollection
            .fetchAssetCollections(withLocalIdentifiers: [localID], options: nil)
            .firstObject
    }

    private static func fetchAlbums(title: String) -> PHFetchResult<PHAssetCollection> {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", title)
        return PHAssetCollection.fetchAssetCollections(
            with: .album, subtype: .any, options: options
        )
    }
}
