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

val yahooJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class YahooChartResponse(val chart: Chart) {
    @Serializable
    data class Chart(val result: List<ResultItem>? = null)

    @Serializable
    data class ResultItem(val meta: Meta)

    @Serializable
    data class Meta(
        val symbol: String,
        val currency: String? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val regularMarketPrice: BigDecimal? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val chartPreviousClose: BigDecimal? = null,
    )
}
