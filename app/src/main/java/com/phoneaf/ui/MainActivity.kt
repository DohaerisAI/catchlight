package com.phoneaf.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private val vm: PhoneAfViewModel by viewModels()
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importModels(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with dark system bars; the UI pads itself with safe-drawing insets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Viewfinder convention: bars hidden, swipe to peek.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = Ink.colorScheme, typography = geistTypography()) {
                CatchlightApp(vm, onImportModel = { importModel.launch(arrayOf("*/*")) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.connect()
    }
}

/** Material 3 type scale with every role set in Geist. */
private fun geistTypography(): Typography {
    val t = Typography()
    fun TextStyle.g() = copy(fontFamily = Ink.Sans)
    return Typography(
        displayLarge = t.displayLarge.g(), displayMedium = t.displayMedium.g(), displaySmall = t.displaySmall.g(),
        headlineLarge = t.headlineLarge.g(), headlineMedium = t.headlineMedium.g(), headlineSmall = t.headlineSmall.g(),
        titleLarge = t.titleLarge.g(), titleMedium = t.titleMedium.g(), titleSmall = t.titleSmall.g(),
        bodyLarge = t.bodyLarge.g(), bodyMedium = t.bodyMedium.g(), bodySmall = t.bodySmall.g(),
        labelLarge = t.labelLarge.g(), labelMedium = t.labelMedium.g(), labelSmall = t.labelSmall.g(),
    )
}
