import SwiftUI
import AppKit
import APTradeDomain

/// Company-news section shown on the asset-detail view. Renders nothing for the no-key case
/// (the standalone News tab carries the "connect a source" guidance) and only shows a header
/// once there are articles.
struct AssetNewsSection: View {
    @Bindable var viewModel: AssetNewsViewModel

    var body: some View {
        Group {
            if viewModel.keyMissing || (viewModel.articles.isEmpty && !viewModel.isLoading) {
                EmptyView()
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text("News")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                    if viewModel.isLoading && viewModel.articles.isEmpty {
                        ProgressView()
                    } else {
                        ForEach(viewModel.articles.prefix(8)) { article in
                            ArticleRow(
                                article: article,
                                isBookmarked: viewModel.isBookmarked(article),
                                onOpen: { NSWorkspace.shared.open(article.url) },
                                onToggleBookmark: { viewModel.toggleBookmark(article) })
                            Divider().overlay(Theme.hairline)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .task { await viewModel.onAppear() }
    }
}
