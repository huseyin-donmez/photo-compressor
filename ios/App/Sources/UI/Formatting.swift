import Foundation

func formatBytes(_ bytes: Int64) -> String {
    switch bytes {
    case ..<1024:
        return "\(bytes) B"
    case ..<1_048_576:
        return String(format: "%.0f KB", Double(bytes) / 1024.0)
    default:
        return String(format: "%.1f MB", Double(bytes) / (1024.0 * 1024.0))
    }
}
