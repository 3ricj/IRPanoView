package com.vilos.irpanoview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vilos.irpanoview.ui.IRPanoViewApp
import com.vilos.irpanoview.ui.theme.IRPanoViewTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IRPanoViewTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IRPanoViewApp()
                }
            }
        }
    }
}
