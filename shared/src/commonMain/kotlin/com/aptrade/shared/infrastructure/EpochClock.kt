package com.aptrade.shared.infrastructure

/** Current wall-clock time in Unix epoch seconds. Platform-native — no java.time in commonMain. */
expect fun epochSecondsNow(): Long
