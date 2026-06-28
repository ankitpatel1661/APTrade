import Foundation

/// Reads local app configuration from `~/.config/aptrade/config.json`. The Finnhub API key
/// lives here, outside the repo, so it is never committed. The value is never logged.
public enum AppConfig {
    private struct File: Decodable { let finnhubAPIKey: String? }

    /// Returns the configured Finnhub key, or `nil` when the file is absent, unreadable,
    /// malformed, or the key is empty/whitespace.
    public static func finnhubAPIKey(path: URL? = nil) -> String? {
        let finalPath = path ?? FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config/aptrade/config.json")
        guard let data = try? Data(contentsOf: finalPath),
              let file = try? JSONDecoder().decode(File.self, from: data),
              let key = file.finnhubAPIKey?.trimmingCharacters(in: .whitespacesAndNewlines),
              !key.isEmpty else { return nil }
        return key
    }
}
