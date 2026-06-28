import SwiftUI
import APTradeDomain

/// One news headline row: thumbnail, headline, source · relative-time, 2-line summary
/// preview, and a bookmark toggle. Tapping the row calls `onOpen`. Reused by the News tab
/// and the asset-detail news section.
struct ArticleRow: View {
    let article: NewsArticle
    let isBookmarked: Bool
    let onOpen: () -> Void
    let onToggleBookmark: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Button(action: onOpen) {
                HStack(alignment: .top, spacing: 12) {
                    thumbnail
                    VStack(alignment: .leading, spacing: 4) {
                        Text(article.headline)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        Text("\(article.source) · \(article.publishedAt.formatted(.relative(presentation: .named)))")
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.textSecondary)
                        if !article.summary.isEmpty {
                            Text(article.summary)
                                .font(.system(size: 12))
                                .foregroundStyle(Theme.textSecondary)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onToggleBookmark) {
                Image(systemName: isBookmarked ? "bookmark.fill" : "bookmark")
                    .font(.system(size: 14))
                    .foregroundStyle(isBookmarked ? Theme.gold : Theme.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 8)
    }

    private var thumbnail: some View {
        AsyncImage(url: article.imageURL) { phase in
            if case .success(let image) = phase {
                image.resizable().scaledToFill()
            } else {
                Rectangle().fill(Theme.surfaceHi)
            }
        }
        .frame(width: 64, height: 64)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}
