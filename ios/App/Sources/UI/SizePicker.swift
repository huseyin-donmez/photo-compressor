import SwiftUI

/// Scroll-wheel target size + KB/MB segmented control — the iOS twin of
/// Android's `SizePicker`. Switching units snaps to the nearest entry so the
/// chosen size stays equivalent.
struct SizePicker: View {
    let unit: SizeUnit
    let value: Int
    let onChange: (SizeUnit, Int) -> Void

    var body: some View {
        VStack(spacing: 12) {
            Picker(
                "Target size",
                selection: Binding(
                    get: { value },
                    set: { onChange(unit, $0) }
                )
            ) {
                ForEach(TargetSizes.values(for: unit), id: \.self) { entry in
                    Text("\(entry)").tag(entry)
                }
            }
            .pickerStyle(.wheel)
            .frame(height: 150)
            .clipped()

            Picker(
                "Unit",
                selection: Binding(
                    get: { unit },
                    set: { newUnit in
                        let bytes = TargetSizes.bytes(unit: unit, value: value)
                        onChange(newUnit, TargetSizes.nearest(unit: newUnit, bytes: bytes))
                    }
                )
            ) {
                ForEach(SizeUnit.allCases) { entry in
                    Text(entry.label).tag(entry)
                }
            }
            .pickerStyle(.segmented)
        }
    }
}
