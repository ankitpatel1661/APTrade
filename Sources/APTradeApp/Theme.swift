import SwiftUI
import APTradeDomain

enum Theme {
    static func changeColor(_ percent: Percentage?) -> Color {
        guard let percent else { return .secondary }
        if percent.isPositive { return .green }
        if percent.isNegative { return .red }
        return .secondary
    }
}
