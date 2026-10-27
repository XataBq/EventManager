package ru.eventpass.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import ru.eventpass.app.navigation.EventPassApp
import ru.eventpass.app.navigation.LinkRequest
import ru.eventpass.core.designsystem.EventPassTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var linkRequest by mutableStateOf<LinkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // An already consumed launch link must not replace the system-restored stack.
        if (savedInstanceState == null && intent.action == Intent.ACTION_VIEW) receiveLink(intent)
        setContent {
            EventPassTheme {
                EventPassApp(linkRequest, onLinkConsumed = { linkRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) receiveLink(intent)
    }

    private fun receiveLink(intent: Intent) {
        linkRequest = LinkRequest(intent.dataString, UUID.randomUUID().toString())
    }
}
