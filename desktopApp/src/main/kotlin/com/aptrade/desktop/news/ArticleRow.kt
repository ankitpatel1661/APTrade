package com.aptrade.desktop.news

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.RemoteThumbnail
import com.aptrade.desktop.designkit.relativeTimeText
import com.aptrade.shared.domain.NewsArticle

/** One news article row, shared by the News tab and the detail-screen News section.
 *
 *  Anatomy: a 64dp rounded thumbnail, a 2-line headline, a "{source} · {relative time}"
 *  meta line, an optional 2-line summary (hidden when blank), and a trailing bookmark
 *  toggle (gold when [bookmarked]). Clicking the row body invokes [onOpen]; the bookmark
 *  button consumes its own click via [onToggleBookmark] and never opens the article.
 *
 *  `now` is captured once at render (`System.currentTimeMillis()/1000`) so all rows share a
 *  single reference point for [relativeTimeText]. */
@Composable
fun ArticleRow(
    article: NewsArticle,
    bookmarked: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onOpen() }
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RemoteThumbnail(url = article.imageUrl, modifier = Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                article.headline,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${article.source} · ${relativeTimeText(article.publishedAtEpochSeconds, now)}",
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.summary.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    article.summary,
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = DK.textSecondary,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        BookmarkButton(bookmarked = bookmarked, onClick = onToggleBookmark)
    }
}

/** A hand-drawn bookmark glyph in a 28dp hit target — filled gold when [bookmarked], a
 *  hollow secondary-tinted outline otherwise. Consumes its own click so tapping it toggles
 *  the bookmark without opening the article. */
@Composable
private fun BookmarkButton(bookmarked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (bookmarked) DK.gold else DK.textSecondary
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
