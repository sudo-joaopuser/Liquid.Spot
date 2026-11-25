package app.liquidspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.liquidspot.R
import app.liquidspot.core.ThemePalette

@Composable
internal fun AccentColorPicker(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Text(stringResource(R.string.pref_accent_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        ThemePalette.ACCENT_PRESETS.forEach { color ->
            val isSelected = color == selected
            Box(
                Modifier
                    .size(40.dp)
                    .border(
                        BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
                        CircleShape,
                    )
                    .padding(4.dp)
                    .background(Color(color), CircleShape)
                    .selectable(isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(color) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    var text by remember { mutableStateOf(ThemePalette.toHex(selected)) }
    LaunchedEffect(selected) { text = ThemePalette.toHex(selected) }
    val parsed = ThemePalette.parseHex(text)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .background(Color(parsed ?: selected), CircleShape),
        )
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(7) },
            enabled = enabled,
            singleLine = true,
            isError = parsed == null,
            label = { Text(stringResource(R.string.pref_accent_custom)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { parsed?.let(onSelect) }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { parsed?.let(onSelect) }, enabled = enabled && parsed != null && parsed != selected) {
            Text(stringResource(R.string.pref_accent_apply))
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.pref_accent_restart),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
