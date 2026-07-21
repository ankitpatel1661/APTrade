import Foundation
import APTradeApplication
import APTradeDomain

/// Persists the most recent full-universe screener scan result as Codable JSON on disk —
/// Infrastructure's first file-backed (as opposed to UserDefaults-backed) store, since a
/// full snapshot across the S&P 500 is too large to comfortably live in `UserDefaults`.
///
/// Writes are atomic (`Data.write(options: .atomic)`), so a crash mid-write never leaves a
/// half-written file. A decode failure (corruption/schema drift) or missing file returns
/// `nil` without touching the file on disk — same no-overwrite guarantee as
/// `UserDefaultsPieStore`, adapted from UserDefaults bytes to file bytes.
public final class FileScreenerSnapshotStore: ScreenerSnapshotStore, @unchecked Sendable {
    private let fileURL: URL

    /// `directory` defaults to `~/Library/Application Support/APTrade` (created on demand);
    /// tests inject a temp directory so they never touch the real Application Support folder.
    public init(directory: URL? = nil) {
        let resolvedDirectory = directory ?? Self.defaultDirectory
        self.fileURL = resolvedDirectory.appendingPathComponent("screener-snapshot.json")
    }

    public func load() -> ScreenerSnapshot? {
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = try? JSONDecoder().decode(ScreenerSnapshot.self, from: data) else {
            return nil
        }
        return snapshot
    }

    public func save(_ snapshot: ScreenerSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        let directory = fileURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: .atomic)
    }

    private static var defaultDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("APTrade", isDirectory: true)
    }
}
