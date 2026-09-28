import SwiftUI

/// Batch run screen — mirrored from Android's BatchScreen: progress + item
/// list while running, summary + share-out when finished, credit gate when
/// paused.
struct BatchView: View {
    let state: BatchState
    let photoSaveFailed: Bool
    let onDone: () -> Void
    let onResume: () -> Void
    let onCancel: () -> Void
    let onGetCredits: () -> Void

    @State private var shareItems: [URL]?

    private var terminal: Int {
        state.items.filter {
            $0.status == .done || $0.status == .failed || $0.status == .cancelled
        }.count
    }

    private var hasPending: Bool {
        state.items.contains {
            $0.status == .pending || $0.status == .running || $0.status == .blocked
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(state.finished ? "Done" : "Resizing…")
                .font(.title2)

            if state.finished {
                finishedBody
            } else {
                runningBody
            }

            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(state.items) { item in
                        ItemRow(item: item) { url in
                            shareItems = [url]
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .sheet(
            isPresented: Binding(
                get: { shareItems != nil },
                set: { if !$0 { shareItems = nil } }
            )
        ) {
            ShareSheet(items: shareItems ?? [])
        }
    }

    private var finishedBody: some View {
        Group {
            Text(
                "\(state.doneCount) of \(state.items.count) photos saved under " +
                "\(formatBytes(state.targetBytes))."
            )
            ForEach(
                state.items.filter { $0.status == .failed }
            ) { item in
                Text("\(item.name): \(item.error ?? "failed")")
                    .font(.caption)
                    .foregroundColor(.red)
            }
            if photoSaveFailed {
                Text(
                    "Photos library access is off — use Share to keep your images."
                )
                .font(.caption)
                .foregroundColor(.orange)
            }
            let outputs = state.outputURLs
            HStack(spacing: 12) {
                if !outputs.isEmpty {
                    Button(
                        outputs.count == 1
                            ? "Share"
                            : "Share \(outputs.count)"
                    ) {
                        shareItems = outputs
                    }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                }
                Button("Done", action: onDone)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private var runningBody: some View {
        Group {
            ProgressView(
                value: state.items.isEmpty ? 0 : Double(terminal) / Double(state.items.count)
            )
            Text("\(terminal) of \(state.items.count)")
                .font(.callout)
                .foregroundColor(.secondary)
            if state.pausedForCredits {
                Text("Out of credits for the remaining photos.")
                    .foregroundColor(.accentColor)
                HStack(spacing: 8) {
                    Button("Get credits", action: onGetCredits)
                        .buttonStyle(.borderedProminent)
                    Button("Stop here", action: onDone)
                        .buttonStyle(.bordered)
                }
            } else {
                HStack(spacing: 8) {
                    if state.running {
                        Button("Cancel", action: onCancel)
                            .buttonStyle(.bordered)
                    } else {
                        Button("Resume", action: onResume)
                            .buttonStyle(.borderedProminent)
                        Button("Stop here", action: onDone)
                            .buttonStyle(.bordered)
                    }
                }
            }
        }
    }
}

private struct ItemRow: View {
    let item: BatchItem
    let onShare: (URL) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(item.name)
                    .fontWeight(.medium)
                    .lineLimit(1)
                Spacer()
                if item.status == .done, let url = item.outputURL {
                    Button {
                        onShare(url)
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 15))
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Share \(item.name)")
                }
            }
            Text(statusLine)
                .font(.caption)
                .foregroundColor(statusColor)
                .lineLimit(2)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
        )
    }

    private var statusLine: String {
        switch item.status {
        case .pending:
            return "Waiting"
        case .running:
            return "Resizing…"
        case .blocked:
            return "Needs a credit"
        case .cancelled:
            return "Cancelled"
        case .failed:
            return item.error ?? "Failed"
        case .done:
            var line = item.passThrough == true
                ? "Saved (original kept)"
                : "Saved (re-encoded)"
            if let width = item.width, let height = item.height {
                line += " · \(width)×\(height)"
            }
            if let bytes = item.bytes {
                line += " · \(formatBytes(bytes))"
            }
            return line
        }
    }

    private var statusColor: Color {
        switch item.status {
        case .failed: return .red
        case .blocked: return .accentColor
        case .done: return .accentColor
        default: return .secondary
        }
    }
}
