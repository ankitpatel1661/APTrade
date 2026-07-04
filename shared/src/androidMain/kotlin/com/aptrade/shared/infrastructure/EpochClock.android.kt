package com.aptrade.shared.infrastructure

actual fun epochSecondsNow(): Long = System.currentTimeMillis() / 1000L
