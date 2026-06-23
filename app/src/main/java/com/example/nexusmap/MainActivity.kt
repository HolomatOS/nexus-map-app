package com.example.nexusmap

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("nexusmap_prefs", Context.MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid_tiles")
        }

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                MapScreen()
            }
        }
    }
}
