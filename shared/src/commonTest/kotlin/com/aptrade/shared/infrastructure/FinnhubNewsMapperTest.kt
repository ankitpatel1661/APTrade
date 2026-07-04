package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.NewsArticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinnhubNewsMapperTest {
    @Test
    fun mapsFullArticleHappyPath() {
        val dto = FinnhubArticleDTO(
            category = "general",
            datetime = 1_700_000_000.0,
            headline = "Markets rally",
            id = 42L,
            image = "https://example.com/img.png",
            related = "AAPL",
            source = "Reuters",
            summary = "Stocks rose today.",
            url = "https://example.com/article",
        )

        val article = FinnhubNewsMapper.article(dto)

        assertEquals(
            NewsArticle(
                id = "42",
                headline = "Markets rally",
                summary = "Stocks rose today.",
                source = "Reuters",
                url = "https://example.com/article",
                imageUrl = "https://example.com/img.png",
                publishedAtEpochSeconds = 1_700_000_000L,
                category = "general",
                relatedSymbol = "AAPL",
            ),
            article,
        )
    }

    @Test
    fun dropsArticleWithBlankHeadline() {
        val dto = FinnhubArticleDTO(headline = "   ", url = "https://example.com/a")
        assertNull(FinnhubNewsMapper.article(dto))
    }

    @Test
    fun dropsArticleWithAbsentHeadline() {
        val dto = FinnhubArticleDTO(headline = null, url = "https://example.com/a")
        assertNull(FinnhubNewsMapper.article(dto))
    }

    @Test
    fun dropsArticleWithMissingUrl() {
        val dto = FinnhubArticleDTO(headline = "Headline", url = null)
        assertNull(FinnhubNewsMapper.article(dto))
    }

    @Test
    fun dropsArticleWithBlankUrl() {
        val dto = FinnhubArticleDTO(headline = "Headline", url = "   ")
        assertNull(FinnhubNewsMapper.article(dto))
    }

    @Test
    fun emptyImageBecomesNull() {
        val dto = FinnhubArticleDTO(headline = "H", url = "https://example.com/a", image = "")
        assertEquals(null, FinnhubNewsMapper.article(dto)?.imageUrl)
    }

    @Test
    fun emptyRelatedBecomesNull() {
        val dto = FinnhubArticleDTO(headline = "H", url = "https://example.com/a", related = "")
        assertEquals(null, FinnhubNewsMapper.article(dto)?.relatedSymbol)
    }

    @Test
    fun idFallsBackToUrlWhenAbsent() {
        val dto = FinnhubArticleDTO(headline = "H", url = "https://example.com/a", id = null)
        assertEquals("https://example.com/a", FinnhubNewsMapper.article(dto)?.id)
    }

    @Test
    fun datetimeAbsentDefaultsToZero() {
        val dto = FinnhubArticleDTO(headline = "H", url = "https://example.com/a", datetime = null)
        assertEquals(0L, FinnhubNewsMapper.article(dto)?.publishedAtEpochSeconds)
    }

    @Test
    fun summaryAndSourceDefaultToEmptyString() {
        val dto = FinnhubArticleDTO(headline = "H", url = "https://example.com/a", summary = null, source = null)
        val article = FinnhubNewsMapper.article(dto)
        assertEquals("", article?.summary)
        assertEquals("", article?.source)
    }

    @Test
    fun articlesListMapsNotNullPreservingOrderOfSurvivors() {
        val dtos = listOf(
            FinnhubArticleDTO(headline = "First", url = "https://example.com/1"),
            FinnhubArticleDTO(headline = null, url = "https://example.com/2"), // dropped
            FinnhubArticleDTO(headline = "Third", url = "https://example.com/3"),
            FinnhubArticleDTO(headline = "Fourth", url = ""), // dropped
            FinnhubArticleDTO(headline = "Fifth", url = "https://example.com/5"),
        )

        val articles = FinnhubNewsMapper.articles(dtos)

        assertEquals(listOf("First", "Third", "Fifth"), articles.map { it.headline })
    }

    @Test
    fun decodesFromFinnhubJsonWithUnknownKeysIgnored() {
        val json = """
            [
              {"category":"general","datetime":1700000000,"headline":"Headline",
               "id":7,"image":"","related":"","source":"Src","summary":"Sum",
               "url":"https://example.com/a","unexpectedField":"ignored"}
            ]
        """.trimIndent()

        val dtos = finnhubJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(FinnhubArticleDTO.serializer()),
            json,
        )
        val articles = FinnhubNewsMapper.articles(dtos)

        assertEquals(1, articles.size)
        assertEquals("Headline", articles[0].headline)
        assertNull(articles[0].imageUrl)
        assertNull(articles[0].relatedSymbol)
    }
}
