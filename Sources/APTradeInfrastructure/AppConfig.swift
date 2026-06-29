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
