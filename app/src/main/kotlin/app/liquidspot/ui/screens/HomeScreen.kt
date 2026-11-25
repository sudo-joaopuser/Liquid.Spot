package app.liquidspot.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.liquidspot.R
import app.liquidspot.core.Constants
import app.liquidspot.ui.SettingsUiState
import app.liquidspot.ui.theme.SectionCard

@Composable
fun HomeScreen(state: SettingsUiState, onRequestScope: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    ScreenColumn(stringResource(R.string.app_name), modifier) {
        SpotifyVersionCard(state.spotifyVersion, state.spotifyTested)

        SectionCard {
            StatusLine(
                ok = state.connected,
                text = stringResource(
                    if (state.connected) R.string.status_service_connected else R.string.status_service_disconnected,
                ),
            )
            state.framework?.let {
                Text(
                    stringResource(R.string.status_framework, it.name, it.version, it.apiVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.connected) {
                Text(
                    stringResource(R.string.status_service_disconnected_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.connected) {
                Spacer(Modifier.height(12.dp))
                StatusLine(
                    ok = state.spotifyInScope,
                    text = stringResource(if (state.spotifyInScope) R.string.status_scope_ok else R.string.status_scope_missing),
                )
                if (!state.spotifyInScope) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = onRequestScope) { Text(stringResource(R.string.action_request_scope)) }
                }
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }


        SectionCard {
            Text(stringResource(R.string.restart_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                enabled = state.spotifyVersion != null,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", Constants.SPOTIFY_PACKAGE, null),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_open_spotify_info)) }
        }
    }
}

@Composable
private fun SpotifyVersionCard(version: String?, tested: Boolean) {
    val colors = MaterialTheme.colorScheme
    val container = if (version != null && tested) colors.primaryContainer else colors.tertiaryContainer
    val content = if (version != null && tested) colors.onPrimaryContainer else colors.onTertiaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (version != null && tested) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (version == null) stringResource(R.string.status_spotify_missing) else stringResource(R.string.status_running_on, version),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (version != null) {
                        Text(
                            stringResource(if (tested) R.string.status_spotify_tested else R.string.status_spotify_untested),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.status_tested_versions), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Constants.TESTED_SPOTIFY_VERSIONS.forEach { testedVersion ->
                    val current = testedVersion == version
                    Text(
                        testedVersion,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (current) colors.onPrimary else content,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (current) colors.primary else content.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
