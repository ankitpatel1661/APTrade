package com.aptrade.shared.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun defaultYahooHttpClient(): HttpClient = HttpClient(OkHttp) { installYahoo() }
