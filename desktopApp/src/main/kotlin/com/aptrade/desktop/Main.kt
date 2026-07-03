package com.aptrade.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "APTrade") {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0C0B09)),
            contentAlignment = Alignment.Center,
        ) {
            Text("APTrade desktop — walking skeleton", color = Color(0xFFD4A94E))
        }
    }
}
