package ru.eventpass.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import dagger.hilt.android.AndroidEntryPoint
import ru.eventpass.app.navigation.EventPassApp
import androidx.activity.viewModels
import ru.eventpass.app.navigation.auth.AppSessionViewModel
import ru.eventpass.core.designsystem.EventPassTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val session: AppSessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // An already consumed launch link must not replace the system-restored stack.
        if (savedInstanceState == null && intent.action == Intent.ACTION_VIEW) receiveLink(intent)
        setContent {
            EventPassTheme {
                EventPassApp(session)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) receiveLink(intent)
    }

    private fun receiveLink(intent: Intent) {
        session.receiveLink(intent.dataString)
        // Delivery now belongs to the retained ViewModel. Never let Activity save a secret URI.
        intent.data = null
        intent.action = Intent.ACTION_MAIN
    }
}
