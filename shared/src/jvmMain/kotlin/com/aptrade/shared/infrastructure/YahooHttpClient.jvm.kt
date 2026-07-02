package com.aptrade.shared.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun defaultYahooHttpClient(): HttpClient = HttpClient(CIO) { installYahoo() }
