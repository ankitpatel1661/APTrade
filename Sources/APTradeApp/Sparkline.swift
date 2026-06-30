import SwiftUI

/// The watchlist's signature element: a minimal intraday price trace with a soft
/// gradient fill, colored by the day's direction. No axes, labels, or chrome —
/// just the shape of today's movement.
struct Sparkline: View {
    let values: [Double]
    let color: Color

    var body: some View {
        // The soft area fill is tuned for the dark canvas. On a light background the
        // same opacity reads as a heavy wash spreading down the cell, so fade it back
        // in light mode while leaving dark mode pixel-for-pixel unchanged.
        let fillTopOpacity = ThemeManager.shared.isDark ? 0.22 : 0.10
        return Canvas { context, size in
            guard values.count > 1,
                  let minValue = values.min(),
                  let maxValue = values.max() else { return }

            let range = maxValue - minValue
            let stepX = size.width / CGFloat(values.count - 1)
            let inset: CGFloat = 2   // keep the stroke off the top/bottom edges

            func point(at index: Int) -> CGPoint {
                let x = CGFloat(index) * stepX
                let normalized = range == 0 ? 0.5 : (values[index] - minValue) / range
                let y = inset + (1 - CGFloat(normalized)) * (size.height - inset * 2)
                return CGPoint(x: x, y: y)
            }

            var line = Path()
            line.move(to: point(at: 0))
            for index in 1..<values.count { line.addLine(to: point(at: index)) }

            var fill = line
            fill.addLine(to: CGPoint(x: size.width, y: size.height))
            fill.addLine(to: CGPoint(x: 0, y: size.height))
            fill.closeSubpath()

            context.fill(fill, with: .linearGradient(
                Gradient(colors: [color.opacity(fillTopOpacity), color.opacity(0.0)]),
                startPoint: CGPoint(x: 0, y: 0),
                endPoint: CGPoint(x: 0, y: size.height)
            ))
            context.stroke(line, with: .color(color),
                           style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
        }
        .accessibilityHidden(true)
    }
}
