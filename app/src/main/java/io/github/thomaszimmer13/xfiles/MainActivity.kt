package io.github.thomaszimmer13.xfiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.thomaszimmer13.xfiles.ui.XFilesApp
import io.github.thomaszimmer13.xfiles.ui.theme.XFilesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XFilesTheme {
                XFilesApp()
            }
        }
    }
}
