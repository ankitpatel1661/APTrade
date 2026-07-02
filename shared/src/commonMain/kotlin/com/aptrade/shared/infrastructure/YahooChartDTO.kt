package com.aptrade.shared.infrastructure

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Reads a JSON number's raw text into an exact BigDecimal (no Double round-trip). */
object BigDecimalWireSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content
        return BigDecimal.parseString(text)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toStringExpanded())
    }
}

/**
 * Reads a JSON array of numbers (with possible `null` entries) into exact BigDecimals,
 * element by element — same no-Double discipline as [BigDecimalWireSerializer], applied
 * to OHLC price arrays. Decode-only: this DTO is never re-serialized.
 */
object BigDecimalListWireSerializer : KSerializer<List<BigDecimal?>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimalList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<BigDecimal?> {
        val array = (decoder as JsonDecoder).decodeJsonElement().jsonArray
        return array.map { element ->
            if (element is JsonNull) null else BigDecimal.parseString(element.jsonPrimitive.content)
        }
    }

    override fun serialize(encoder: Encoder, value: List<BigDecimal?>) {
        throw UnsupportedOperationException("BigDecimalListWireSerializer is decode-only")
    }
}

val yahooJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class YahooChartResponse(val chart: Chart) {
    @Serializable
    data class Chart(val result: List<ResultItem>? = null)

    @Serializable
    data class ResultItem(
        val meta: Meta,
        val timestamp: List<Long>? = null,
        val indicators: Indicators? = null,
    )

    @Serializable
    data class Meta(
        val symbol: String,
        val currency: String? = null,
        val instrumentType: String? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val regularMarketPrice: BigDecimal? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val chartPreviousClose: BigDecimal? = null,
        val longName: String? = null,
        val shortName: String? = null,
    )

    @Serializable
    data class Indicators(val quote: List<QuoteBlock>? = null)

    @Serializable
    data class QuoteBlock(
        @Serializable(with = BigDecimalListWireSerializer::class)
        val open: List<BigDecimal?>? = null,
        @Serializable(with = BigDecimalListWireSerializer::class)
        val high: List<BigDecimal?>? = null,
        @Serializable(with = BigDecimalListWireSerializer::class)
        val low: List<BigDecimal?>? = null,
        @Serializable(with = BigDecimalListWireSerializer::class)
        val close: List<BigDecimal?>? = null,
        val volume: List<Double?>? = null,
    )
}
