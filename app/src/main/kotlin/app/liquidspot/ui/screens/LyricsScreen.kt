package app.liquidspot.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.liquidspot.R
import app.liquidspot.core.AiFiles
import app.liquidspot.core.LyricsSource
import app.liquidspot.ui.AiUiState
import app.liquidspot.ui.SettingsUiState
import app.liquidspot.ui.theme.SectionCard

class LyricsActions(
    val onToggle: (LyricsSource, Boolean) -> Unit,
    val onMove: (LyricsSource, Int) -> Unit,
    val onResetOrder: () -> Unit,
    val onPaxSenixKey: (String) -> Unit,
    val onAiDownload: () -> Unit,
    val onAiImport: (Uri) -> Unit,
    val onAiCancel: () -> Unit,
    val onAiRemove: () -> Unit,
    val onAiCacheEnabled: (Boolean) -> Unit,
    val onAiCacheLimit: (Int) -> Unit,
    val onAiCacheClear: () -> Unit,
)

@Composable
fun LyricsScreen(state: SettingsUiState, actions: LyricsActions, modifier: Modifier = Modifier) {
    ScreenColumn(stringResource(R.string.lyrics_screen_title), modifier) {
        Text(
            stringResource(R.string.lyrics_screen_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.connected) {
            Text(
                stringResource(R.string.features_need_service),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SectionCard {
            state.lyricsOrder.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SourceRow(
                    position = index + 1,
                    source = source,
                    enabled = source in state.lyricsEnabled,
                    missingKey = source.requiresKey && state.paxSenixKey.isBlank(),
                    canMoveUp = index > 0,
                    canMoveDown = index < state.lyricsOrder.lastIndex,
                    interactive = state.connected,
                    actions = actions,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = actions.onResetOrder, enabled = state.connected) {
                Text(stringResource(R.string.lyrics_reset_order))
            }
        }

        SectionCard { AiCard(state.ai, state.connected, actions) }

        SectionCard {
            Text(stringResource(R.string.lyrics_paxsenix_key), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.lyrics_paxsenix_key_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            var key by remember { mutableStateOf(state.paxSenixKey) }
            LaunchedEffect(state.paxSenixKey) { key = state.paxSenixKey }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    enabled = state.connected,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.onPaxSenixKey(key) }, enabled = state.connected && key.trim() != state.paxSenixKey) {
                    Text(stringResource(R.string.lyrics_save))
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    position: Int,
    source: LyricsSource,
    enabled: Boolean,
    missingKey: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    interactive: Boolean,
    actions: LyricsActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "$position",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(source.label, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (missingKey) R.string.lyrics_needs_key else source.detail),
                style = MaterialTheme.typography.bodySmall,
                color = if (missingKey) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.onMove(source, -1) }, enabled = interactive && canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.lyrics_move_up))
        }
        IconButton(onClick = { actions.onMove(source, 1) }, enabled = interactive && canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.lyrics_move_down))
        }
        Switch(checked = enabled, onCheckedChange = { actions.onToggle(source, it) }, enabled = interactive)
    }
}

@Composable
private fun AiCard(ai: AiUiState, connected: Boolean, actions: LyricsActions) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(actions.onAiImport) }
    Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.ai_summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    val status = when {
        ai.installed -> stringResource(
            R.string.ai_status_ready,
            "%.0f MB".format(ai.modelSize / 1_048_576.0),
            stringResource(if (ai.modelSource == AiFiles.SOURCE_IMPORT) R.string.ai_source_import else R.string.ai_source_download),
        )
        ai.modelSource != null -> stringResource(R.string.ai_status_runtime_missing)
        else -> stringResource(R.string.ai_status_missing)
    }
    Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    ai.error?.let {
        Text(stringResource(R.string.ai_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
    if (ai.busy) {
        LinearProgressIndicator(progress = { ai.progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.ai_progress, (ai.progress * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onAiCancel) { Text(stringResource(R.string.ai_cancel)) }
        }
        return
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        if (!ai.installed) {
            Button(onClick = actions.onAiDownload, enabled = connected, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_download, "%.0f MB".format(AiFiles.MODEL_BYTES / 1_048_576.0)), maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = connected, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_import), maxLines = 1)
        }
    }
    if (ai.modelSource != null) {
        TextButton(onClick = actions.onAiRemove, enabled = connected) { Text(stringResource(R.string.ai_remove)) }
        AiCacheSettings(ai, connected, actions)
    }
}

@Composable
private fun AiCacheSettings(ai: AiUiState, connected: Boolean, actions: LyricsActions) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_cache_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ai_cache_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = ai.cacheEnabled, onCheckedChange = actions.onAiCacheEnabled, enabled = connected)
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.ai_cache_limit), style = MaterialTheme.typography.labelLarge)
    Row(modifier = Modifier.fillMaxWidth()) {
        AiFiles.CACHE_LIMIT_OPTIONS_MB.forEach { mb ->
            FilterChip(
                selected = ai.cacheLimitMb == mb,
                onClick = { actions.onAiCacheLimit(mb) },
                enabled = connected && ai.cacheEnabled,
                label = { Text("$mb MB") },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    TextButton(onClick = actions.onAiCacheClear, enabled = connected && !ai.cacheCleared) {
        Text(stringResource(if (ai.cacheCleared) R.string.ai_cache_cleared else R.string.ai_cache_clear))
    }
}
