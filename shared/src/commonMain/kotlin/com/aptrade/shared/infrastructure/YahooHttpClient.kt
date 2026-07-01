package com.aptrade.shared.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** Shared client config: JSON content negotiation + non-throwing status handling. */
fun HttpClientConfig<*>.installYahoo() {
    install(ContentNegotiation) { json(yahooJson) }
    expectSuccess = false
}

fun defaultYahooHttpClient(): HttpClient = HttpClient(CIO) { installYahoo() }
