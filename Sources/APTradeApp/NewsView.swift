import SwiftUI
import APTradeApplication
import APTradeDomain

struct NewsView: View {
    var switcher: AnyView
    @State private var viewModel = CompositionRoot.makeNewsViewModel()
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 12) {
                    switcher
                        .padding(.horizontal, 24)
                        .padding(.top, 8)
                    if viewModel.keyMissing {
                        Spacer(); noKeyState; Spacer()
                    } else {
                        controls
                        Divider().overlay(Theme.hairline)
                        content
                    }
                }
            }
            .frame(minWidth: 560, minHeight: 640)
            .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
            .task { await viewModel.onAppear() }
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
            HStack(spacing: 4) {
                ForEach(NewsCategory.allCases, id: \.self) { item in
                    let selected = viewModel.category == item && !viewModel.showingSaved
                    Button {
                        viewModel.showingSaved = false
                        Task { await viewModel.setCategory(item) }
                    } label: {
                        Text(categoryTitle(item))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .background { if selected { Capsule().fill(Theme.surfaceHi) } }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    viewModel.showingSaved.toggle()
                } label: {
                    Label(tr(.saved), systemImage: viewModel.showingSaved ? "bookmark.fill" : "bookmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(viewModel.showingSaved ? Theme.gold : Theme.textSecondary)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass").foregroundStyle(Theme.textSecondary)
                TextField(tr(.filterHeadlinesPlaceholder), text: $viewModel.filter)
                    .textFieldStyle(.plain)
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textPrimary)
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(Theme.surface, in: Capsule())
            .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
        }
        .padding(.horizontal, 24)
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading && viewModel.visibleArticles.isEmpty {
            Spacer(); ProgressView(); Spacer()
        } else if viewModel.visibleArticles.isEmpty {
            Spacer(); emptyState; Spacer()
        } else {
            List(viewModel.visibleArticles) { article in
                ArticleRow(
                    article: article,
                    isBookmarked: viewModel.isBookmarked(article),
                    onOpen: { openURL(article.url) },
                    onToggleBookmark: { viewModel.toggleBookmark(article) })
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "newspaper").font(.system(size: 34)).foregroundStyle(Theme.textSecondary)
            Text(viewModel.showingSaved ? tr(.noSavedArticles) : tr(.noHeadlinesRightNow))
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            if !viewModel.showingSaved {
                Button(tr(.refresh)) { Task { await viewModel.load() } }
                    .buttonStyle(.plain)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.gold)
            }
        }
    }

    private var noKeyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "newspaper").font(.system(size: 34)).foregroundStyle(Theme.textSecondary)
            Text(tr(.connectNewsSource))
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            Text(tr(.finnhubKeyInstructions))
                .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center).frame(maxWidth: 360)
        }
    }

    private func categoryTitle(_ category: NewsCategory) -> String {
        switch category {
        case .general: return tr(.newsGeneral)
        case .crypto:  return tr(.cryptoLabel)
        case .merger:  return tr(.newsMerger)
        }
    }
}
