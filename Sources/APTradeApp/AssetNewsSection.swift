import SwiftUI
import APTradeDomain

/// Company-news section shown on the asset-detail view. Renders nothing (EmptyView) for the
/// no-key case (the standalone News tab carries the "connect a source" guidance) and when the
/// fetch has finished with no articles. Otherwise shows the "News" header with a loading
/// spinner while the fetch is in flight, then the article rows once they arrive.
struct AssetNewsSection: View {
    @Bindable var viewModel: AssetNewsViewModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        Group {
            if viewModel.keyMissing || (viewModel.articles.isEmpty && !viewModel.isLoading) {
                EmptyView()
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text(tr(.news))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                    if viewModel.isLoading && viewModel.articles.isEmpty {
                        ProgressView()
                    } else {
                        ForEach(viewModel.articles.prefix(8)) { article in
                            ArticleRow(
                                article: article,
                                isBookmarked: viewModel.isBookmarked(article),
                                onOpen: { openURL(article.url) },
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
