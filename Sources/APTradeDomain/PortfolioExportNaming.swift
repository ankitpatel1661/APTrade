import Foundation

/// Builds the date-stamped filename for an exported portfolio statement. Pure and
/// platform-agnostic so macOS (`NSSavePanel`) and iOS (`.fileExporter`) share one source
/// of truth for the name. The stem uses a POSIX locale so it is stable across regions.
public enum PortfolioExportNaming {
    public static func fileStem(on date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        return "APTrade-Portfolio-\(formatter.string(from: date))"
    }

    public static func filename(for format: PortfolioExportFormat, on date: Date = Date()) -> String {
        "\(fileStem(on: date)).\(format.fileExtension)"
    }
}
