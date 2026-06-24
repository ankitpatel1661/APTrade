import SwiftUI
import APTradeDomain

// MARK: - Brand

/// The APTrade wordmark: "AP" in the logo's gold gradient, "Trade" in silver.
struct BrandMark: View {
    var size: CGFloat = 19

    var body: some View {
        HStack(spacing: 0) {
            Text("AP")
                .foregroundStyle(Theme.goldGradient)
            Text("Trade")
                .foregroundStyle(Theme.silver)
        }
        .font(.system(size: size, weight: .bold))
        .tracking(0.5)
        .accessibilityLabel("APTrade")
    }
}

/// A pulsing gold badge that marks the prices as live-updating.
struct LiveBadge: View {
    @State private var pulsing = false

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(Theme.gold)
                .frame(width: 6, height: 6)
                .opacity(pulsing ? 0.35 : 1)
            Text("LIVE")
                .font(.system(size: 10, weight: .bold))
                .tracking(1.6)
        }
        .foregroundStyle(Theme.gold)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Theme.gold.opacity(0.10), in: Capsule())
        .overlay(Capsule().stroke(Theme.gold.opacity(0.28), lineWidth: 1))
        .onAppear {
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                pulsing = true
            }
        }
        .accessibilityLabel("Live prices")
    }
}

// MARK: - Superscript price

/// Renders money with the integer part large and the cents raised and smaller —
/// the "$131⁶³" treatment that gives the reference its distinctive numeric voice.
struct SuperscriptPrice: View {
    let money: Money
    var size: CGFloat = 34
    var weight: Font.Weight = .semibold
    var color: Color = Theme.textPrimary

    var body: some View {
        let parts = Self.split(money)
        HStack(alignment: .top, spacing: 0) {
            Text(parts.symbol)
                .font(.system(size: size * 0.5, weight: weight).monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
                .baselineOffset(size * 0.40)
                .padding(.trailing, 1)
            Text(parts.whole)
                .font(.system(size: size, weight: weight).monospacedDigit())
            Text(parts.fraction)
                .font(.system(size: size * 0.5, weight: weight).monospacedDigit())
                .foregroundStyle(color.opacity(0.85))
                .baselineOffset(size * 0.40)
                .padding(.leading, 1)
        }
        .foregroundStyle(color)
        .accessibilityElement()
        .accessibilityLabel(money.formatted)
    }

    /// Splits a money value into currency symbol, grouped whole part, and two-digit
    /// fraction, formatting through one number formatter so grouping stays locale-correct.
    static func split(_ money: Money) -> (symbol: String, whole: String, fraction: String) {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .currency
        f.currencyCode = money.currencyCode
        let symbol = f.currencySymbol ?? "$"

        let d = NumberFormatter()
        d.locale = Locale(identifier: "en_US")
        d.numberStyle = .decimal
        d.minimumFractionDigits = 2
        d.maximumFractionDigits = 2

        let negative = money.amount < 0
        let magnitude = abs(money.amount) as NSDecimalNumber
        let text = d.string(from: magnitude) ?? "\(magnitude)"
        let pieces = text.split(separator: ".", maxSplits: 1)
        let whole = (negative ? "-" : "") + String(pieces.first ?? "0")
        let fraction = pieces.count > 1 ? String(pieces[1]) : "00"
        return (symbol, whole, fraction)
    }
}

// MARK: - Change pill

/// A bordered, faintly tinted pill carrying a percentage change in its own color —
/// the reference's "+1,94 %" chip.
struct ChangePill: View {
    let percent: Percentage?

    var body: some View {
        let color = Theme.changeColor(percent)
        Text(percent?.formatted ?? "—")
            .font(.system(size: 12, weight: .semibold).monospacedDigit())
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(color.opacity(0.24), lineWidth: 1)
            )
    }
}

// MARK: - Kind toggle

/// The watchlist's primary structural device: a segmented control that swaps the list
/// between Stocks, ETFs, and Crypto. One category is visible at a time.
struct KindToggle: View {
    @Binding var selection: AssetKind
    let counts: [AssetKind: Int]
    @Namespace private var pill

    private let kinds: [AssetKind] = [.stock, .etf, .crypto]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(kinds, id: \.self) { kind in
                segment(kind)
            }
        }
        .padding(4)
        .background(Theme.surface, in: Capsule())
        .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
    }

    private func segment(_ kind: AssetKind) -> some View {
        let selected = selection == kind
        let count = counts[kind] ?? 0
        return Button {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) { selection = kind }
        } label: {
            HStack(spacing: 7) {
                Text(title(kind))
                    .font(.system(size: 13, weight: .semibold))
                if count > 0 {
                    Text("\(count)")
                        .font(.system(size: 11, weight: .semibold).monospacedDigit())
                        .foregroundStyle(selected ? Theme.gold : Theme.textTertiary)
                }
            }
            .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background {
                if selected {
                    Capsule()
                        .fill(Theme.surfaceHi)
                        .overlay(Capsule().stroke(Theme.gold.opacity(0.40), lineWidth: 1))
                        .matchedGeometryEffect(id: "pill", in: pill)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func title(_ kind: AssetKind) -> String {
        switch kind {
        case .stock: return "Stocks"
        case .etf: return "ETFs"
        case .crypto: return "Crypto"
        }
    }
}

// MARK: - Pulse bar

/// A thin advancers/decliners split — the screen's quiet "pulse". Mint width is the
/// share of names up on the day, coral the share down.
struct PulseBar: View {
    let advancers: Int
    let decliners: Int

    var body: some View {
        GeometryReader { geo in
            let total = max(advancers + decliners, 1)
            let upWidth = geo.size.width * CGFloat(advancers) / CGFloat(total)
            HStack(spacing: 0) {
                Theme.up.frame(width: upWidth)
                Theme.down
            }
            .clipShape(Capsule())
        }
        .frame(height: 4)
    }
}

// MARK: - Timeframe bar

/// Underline-selected timeframe row for the detail chart (1D / 1W / 1M / 1Y).
struct TimeframeBar: View {
    let selection: Timeframe
    let onSelect: (Timeframe) -> Void
    @Namespace private var underline

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Timeframe.allCases, id: \.self) { tf in
                let selected = tf == selection
                Button { onSelect(tf) } label: {
                    VStack(spacing: 6) {
                        Text(tf.displayName)
                            .font(.system(size: 13, weight: .semibold).monospacedDigit())
                            .foregroundStyle(selected ? Theme.gold : Theme.textSecondary)
                        ZStack {
                            Capsule().fill(.clear).frame(height: 2)
                            if selected {
                                Capsule().fill(Theme.gold).frame(height: 2)
                                    .matchedGeometryEffect(id: "tf", in: underline)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.85), value: selection)
    }
}

// MARK: - Stat tile

/// One labeled figure in the detail view's key-stats grid (the reference's "Kennzahlen").
struct StatTile: View {
    let label: String
    let value: String
    var valueColor: Color = Theme.textPrimary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            Text(value)
                .font(.system(size: 16, weight: .semibold).monospacedDigit())
                .foregroundStyle(valueColor)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
