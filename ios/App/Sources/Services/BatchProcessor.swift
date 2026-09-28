import Foundation
import ImageResizerCore
import Photos

enum ItemStatus: Sendable, Equatable {
    case pending, running, done, failed, blocked, cancelled
}

struct BatchItem: Identifiable {
    let id: UUID
    let name: String
    let sourceURL: URL
    var status: ItemStatus = .pending
    var error: String?
    var bytes: Int64?
    var width: Int?
    var height: Int?
    var passThrough: Bool?
    var outputURL: URL?
}

struct BatchState {
    var items: [BatchItem]
    var targetBytes: Int64
    var running = false
    var pausedForCredits = false
    var cancelled = false

    /// Mirrors Android: nothing left to run or show as blocked.
    var finished: Bool {
        !running && !pausedForCredits && !items.contains {
            $0.status == .pending || $0.status == .running || $0.status == .blocked
        }
    }

    var doneCount: Int { items.filter { $0.status == .done }.count }

    var outputURLs: [URL] {
        items.compactMap { $0.status == .done ? $0.outputURL : nil }
    }
}

/// Thread-safe cancellation flag read by the core resizer's `isCancelled`.
final class CancellationFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false

    var isCancelled: Bool {
        get { lock.lock(); defer { lock.unlock() }; return cancelled }
        set { lock.lock(); defer { lock.unlock() }; cancelled = newValue }
    }
}

/// Sequential batch orchestration: credit gate before work, one credit per
/// successful re-encode (pass-through and failures are free), pause when
/// credits run out — the iOS twin of Android's BatchProcessor.
@MainActor
final class BatchProcessor: ObservableObject {
    @Published private(set) var state: BatchState?
    /// Set when saving into Photos failed (denied, etc.); outputs are still
    /// shareable from the done screen.
    @Published private(set) var photoSaveFailed = false

    private let store: Store
    private let cancel = CancellationFlag()
    private var loop: Task<Void, Never>?

    init(store: Store) {
        self.store = store
    }

    func start(sources: [URL], targetBytes: Int64) {
        guard !sources.isEmpty else { return }
        cancel.isCancelled = false
        photoSaveFailed = false
        state = BatchState(
            items: sources.map { url in
                BatchItem(id: UUID(), name: url.lastPathComponent, sourceURL: url)
            },
            targetBytes: targetBytes
        )
        state?.running = true
        launch()
    }

    /// Credits granted (+5) or premium unlocked: unblock and continue.
    func resume() {
        guard var batch = state, batch.pausedForCredits else { return }
        guard store.state.isPremium || store.state.remainingFreeCredits > 0 else { return }
        batch.pausedForCredits = false
        batch.running = true
        batch.items = batch.items.map { item in
            var item = item
            if item.status == .blocked { item.status = .pending }
            return item
        }
        state = batch
        launch()
    }

    func cancelBatch() {
        cancel.isCancelled = true
        guard var batch = state else { return }
        batch.cancelled = true
        batch.running = false
        batch.items = batch.items.map { item in
            var item = item
            if item.status == .pending || item.status == .running ||
                item.status == .blocked {
                item.status = .cancelled
            }
            return item
        }
        state = batch
    }

    /// Done / Stop here: tear down and release the temp outputs.
    func clear() {
        loop?.cancel()
        loop = nil
        cancel.isCancelled = false
        state = nil
        try? FileManager.default.removeItem(at: Self.outputDir())
    }

    // MARK: - Loop

    private func launch() {
        loop?.cancel()
        loop = Task { await run() }
    }

    private func run() async {
        while let batch = state,
              let index = batch.items.firstIndex(where: { $0.status == .pending }) {
            if cancel.isCancelled || Task.isCancelled {
                markCancelled(from: index)
                return
            }

            let item = batch.items[index]
            let target = batch.targetBytes
            let session = ImageIOResizeSession(url: item.sourceURL)

            // Header-only preflight (never decodes) decides the credit gate.
            let header: ImageHeader
            do {
                header = try session.readHeader()
            } catch {
                fail(index, Self.message(for: error))
                continue
            }
            let needsWork = header.byteSize > target
            if needsWork && !store.state.isPremium && store.state.remainingFreeCredits <= 0 {
                pauseForCredits(from: index)
                return
            }

            setStatus(index, .running)

            let flag = cancel
            let outcome: Result<ResizeResult, Error> = await Task.detached(
                priority: .userInitiated
            ) {
                var resizer = TargetSizeResizer()
                resizer.isCancelled = { flag.isCancelled }
                return Result { try resizer.run(session: session, targetBytes: target) }
            }.value

            switch outcome {
            case .failure(let error):
                if case ResizeError.cancelled = error {
                    markCancelled(from: index)
                    return
                }
                fail(index, Self.message(for: error))

            case .success(let result):
                let outputURL = Self.outputDir()
                    .appendingPathComponent("\(item.id.uuidString).\(Self.outputExtension(for: result.report))")
                do {
                    try DiskOutput.writeVerified(
                        result.data, to: outputURL, targetBytes: target
                    )
                } catch {
                    fail(index, Self.message(for: error))
                    continue
                }
                // Photos album is best-effort; share-out always works.
                do {
                    try await PhotoLibraryWriter.save(fileURL: outputURL)
                } catch {
                    photoSaveFailed = true
                }
                if result.report.outcome == .processed {
                    store.consumeCredit()
                }
                complete(index, report: result.report, url: outputURL)
            }
        }
        if state?.running == true {
            state?.running = false
        }
    }

    // MARK: - State transitions

    private func setStatus(_ index: Int, _ status: ItemStatus) {
        state?.items[index].status = status
    }

    private func complete(_ index: Int, report: ResizeReport, url: URL) {
        guard var batch = state else { return }
        batch.items[index].status = .done
        batch.items[index].bytes = report.bytes
        batch.items[index].width = report.pixelWidth
        batch.items[index].height = report.pixelHeight
        batch.items[index].passThrough = report.outcome == .passThrough
        batch.items[index].outputURL = url
        state = batch
    }

    private func fail(_ index: Int, _ message: String) {
        state?.items[index].status = .failed
        state?.items[index].error = message
    }

    private func pauseForCredits(from index: Int) {
        guard var batch = state else { return }
        for i in index..<batch.items.count {
            if batch.items[i].status == .pending || batch.items[i].status == .running {
                batch.items[i].status = .blocked
            }
        }
        batch.running = false
        batch.pausedForCredits = true
        state = batch
    }

    private func markCancelled(from index: Int) {
        guard var batch = state else { return }
        for i in index..<batch.items.count {
            if batch.items[i].status == .pending || batch.items[i].status == .running ||
                batch.items[i].status == .blocked {
                batch.items[i].status = .cancelled
            }
        }
        batch.running = false
        state = batch
    }

    // MARK: - Helpers

    private static func outputDir() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("outputs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func outputExtension(for report: ResizeReport) -> String {
        guard let format = report.outputFormat else {
            return "jpg" // pass-through keeps the source container
        }
        switch format {
        case .jpeg: return "jpg"
        case .png: return "png"
        case .heic: return "heic"
        case .webp: return "webp"
        }
    }

    private static func message(for error: Error) -> String {
        switch error {
        case ResizeError.corrupted: return "Couldn't read this photo"
        case ResizeError.unsupportedFormat: return "Unsupported image format"
        case ResizeError.outOfMemory: return "Ran out of memory"
        case ResizeError.storageFull: return "Not enough storage"
        case ResizeError.encodeFailed: return "Encoding failed"
        case ResizeError.outputSaveFailed: return "Couldn't save the output"
        case ResizeError.targetExceeded: return "Couldn't get under the limit"
        case ResizeError.targetInfeasible: return "Couldn't reach this limit"
        case ResizeError.cancelled: return "Cancelled"
        default: return "Failed"
        }
    }
}
