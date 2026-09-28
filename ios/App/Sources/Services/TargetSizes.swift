import Foundation

/// Mirror of `android/.../ui/SizePicker.kt` — same curated wheels, same
/// byte math, same nearest-entry snap when the unit switches.
enum SizeUnit: String, CaseIterable, Identifiable {
    case kb, mb
    var id: String { rawValue }
    var label: String { rawValue.uppercased() }
}

enum TargetSizes {
    static let kbValues = [100, 125, 150, 200, 250, 300, 400, 500, 750, 1000]
    static let mbValues = [1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 25, 30, 50, 75, 100]
    static let defaultUnit: SizeUnit = .mb
    static let defaultValue = 5

    static func values(for unit: SizeUnit) -> [Int] {
        switch unit {
        case .kb: return kbValues
        case .mb: return mbValues
        }
    }

    static func bytes(unit: SizeUnit, value: Int) -> Int64 {
        switch unit {
        case .kb: return Int64(value) * 1024
        case .mb: return Int64(value) * 1024 * 1024
        }
    }

    /// Wheel entry closest to `bytes`; used to snap when the unit switches.
    static func nearest(unit: SizeUnit, bytes: Int64) -> Int {
        let values = values(for: unit)
        return values.min { a, b in
            abs(self.bytes(unit: unit, value: a) - bytes) <
                abs(self.bytes(unit: unit, value: b) - bytes)
        } ?? values[0]
    }
}
