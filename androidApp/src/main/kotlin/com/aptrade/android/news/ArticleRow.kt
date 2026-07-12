package com.aptrade.android.news

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aptrade.shared.domain.NewsArticle

/** One news article row — text-first (no thumbnail: coil isn't a dependency of this app,
 *  unlike desktop's `RemoteThumbnail`; per the brief this stays a headline/source/time row).
 *  Headline (2 lines), a "{source} · {relative time}" meta line (relative time via the
 *  platform-idiomatic `DateUtils.getRelativeTimeSpanString` rather than reimplementing
 *  desktop's `relativeTimeText`), an optional 2-line summary, and a trailing bookmark toggle
 *  (tinted primary when [bookmarked]) drawn as a hand-drawn glyph — mirrors desktop
 *  `ArticleRow.kt`'s `BookmarkButton` since no bookmark glyph ships in `material-icons-core`
 *  (same reasoning as `WatchlistScreen`'s hand-drawn bell). Tapping the row body invokes
 *  [onOpen]; the bookmark button consumes its own click via [onToggleBookmark] and never
 *  opens the article. */
@Composable
fun ArticleRow(
    article: NewsArticle,
    bookmarked: Boolean,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                article.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${article.source} · ${relativeTime(article.publishedAtEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.summary.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    article.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        BookmarkButton(bookmarked = bookmarked, onClick = onToggleBookmark)
    }
}

/** Relative time text via the platform's own `DateUtils.getRelativeTimeSpanString` (e.g.
 *  "2 hours ago", "3 days ago") — the Android-idiomatic counterpart to desktop's hand-rolled
 *  `relativeTimeText`, with minute resolution (matches the app-wide 15s/poll-adjacent
 *  granularity elsewhere; seconds-level precision isn't meaningful for news). */
private fun relativeTime(epochSeconds: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1_000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

@Composable
private fun BookmarkButton(bookmarked: Boolean, onClick: () -> Unit) {
    val tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val description = if (bookmarked) "Remove bookmark" else "Add bookmark"
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 13.dp, height = 16.dp)) {
            val w = size.width
            val h = size.height
            val notch = h * 0.28f
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
                lineTo(w / 2f, h - notch)
                lineTo(0f, h)
                close()
            }
            if (bookmarked) {
                drawPath(path, color = tint)
            } else {
                drawPath(path, color = tint, style = Stroke(width = 1.4.dp.toPx()))
            }
        }
    }
}
