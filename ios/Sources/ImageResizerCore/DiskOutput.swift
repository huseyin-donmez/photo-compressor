import Foundation

/// Final on-disk verification — the hard guarantee that output ≤ target.
public enum DiskOutput {
    /// Atomically writes `data` and re-checks the real file size.
    /// Deletes the file and throws `targetExceeded` if the guarantee is violated
    /// (belt & braces: the search already measured these exact bytes).
    @discardableResult
    public static func writeVerified(_ data: Data, to url: URL,
                                     targetBytes: Int64) throws -> Int64 {
        do {
            try data.write(to: url, options: .atomic)
        } catch let error as NSError where error.domain == NSPOSIXErrorDomain
            && (error.code == 28 /* ENOSPC */ || error.code == 45 /* EDQUOT */) {
            throw ResizeError.storageFull
        } catch {
            throw ResizeError.outputSaveFailed
        }

        let size: Int64
        do {
            size = try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64
                ?? Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw ResizeError.outputSaveFailed
        }

        guard size <= targetBytes else {
            try? FileManager.default.removeItem(at: url)
            throw ResizeError.targetExceeded(measured: size, target: targetBytes)
        }
        return size
    }
}
