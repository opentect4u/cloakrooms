package com.synergic.cloakroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.ui.components.AppTopBar

/**
 * Shown instead of the app while a super admin or admin has no network: they work only online.
 * It goes away by itself when the network comes back.
 */
@Composable
fun OnlineRequiredScreen(session: UserSession, onLogout: () -> Unit) {
    val role = if (session.isSuperAdmin) "Super Admin" else "Admin"
    Scaffold(topBar = { AppTopBar("Cloak Room") }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.WifiOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Internet needed", style = MaterialTheme.typography.headlineSmall)
            Text(
                "$role accounts work only online. Connect to Wi-Fi or mobile data; this screen closes by itself.",
                textAlign = TextAlign.Center,
            )
            Text("Logged in as ${session.userName} (${session.userId})", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Logout") }
        }
    }
}
