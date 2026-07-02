package com.aptrade.shared.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun defaultYahooHttpClient(): HttpClient = HttpClient(Darwin) { installYahoo() }
