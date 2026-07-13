import Foundation

/// Reads local app configuration from `~/.config/aptrade/config.json`. The Finnhub API key
/// lives here, outside the repo, so it is never committed. The value is never logged.
public enum AppConfig {
    private struct File: Decodable { let finnhubAPIKey: String? }

    /// Returns the configured Finnhub key, or `nil` when the file is absent, unreadable,
    /// malformed, or the key is empty/whitespace.
    public static func finnhubAPIKey(path: URL? = nil) -> String? {
        guard let finalPath = path ?? defaultConfigPath else { return nil }
        guard let data = try? Data(contentsOf: finalPath),
              let file = try? JSONDecoder().decode(File.self, from: data),
              let key = file.finnhubAPIKey?.trimmingCharacters(in: .whitespacesAndNewlines),
              !key.isEmpty else { return nil }
        return key
    }

    /// Persists `key` into the config file (trimmed; nil/blank removes the field) — the
    /// in-app settings-field counterpart of the manual file drop, added because iOS's
    /// sandboxed config path isn't user-reachable. Merge-writes: any other fields already
    /// in the file are preserved (a corrupt file is replaced wholesale — same tolerance as
    /// the read path). Creates intermediate directories. The key value is never logged.
    /// Returns whether the write landed.
    @discardableResult
    public static func saveFinnhubAPIKey(_ key: String?, path: URL? = nil) -> Bool {
        guard let finalPath = path ?? defaultConfigPath else { return false }
        var object: [String: Any] = [:]
        if let data = try? Data(contentsOf: finalPath),
           let existing = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            object = existing
        }
        let trimmed = key?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            object["finnhubAPIKey"] = trimmed
        } else {
            object.removeValue(forKey: "finnhubAPIKey")
        }
        do {
            try FileManager.default.createDirectory(
                at: finalPath.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
            try data.write(to: finalPath, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    /// The default config file location. macOS uses the user's home directory (outside the
    /// app sandbox); iOS has no `homeDirectoryForCurrentUser` API, so it falls back to the
    /// app's Documents directory, which is sandbox-valid.
    private static var defaultConfigPath: URL? {
        #if os(macOS)
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config/aptrade/config.json")
        #else
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent(".config/aptrade/config.json")
        #endif
    }
}
