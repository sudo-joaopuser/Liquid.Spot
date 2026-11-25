package app.liquidspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.liquidspot.R
import app.liquidspot.core.GlassStyle
import app.liquidspot.core.NavBarLayout

private val NavBarLayout.title: Int
    get() = when (this) {
        NavBarLayout.THREE_TABS -> R.string.navbar_layout_three_tabs
        NavBarLayout.SEARCH_BUBBLE -> R.string.navbar_layout_search_bubble
        NavBarLayout.CREATE_BUBBLE -> R.string.navbar_layout_create_bubble
    }

private val GlassStyle.title: Int
    get() = when (this) {
        GlassStyle.LIQUID -> R.string.glass_style_liquid
        GlassStyle.FROSTED -> R.string.glass_style_frosted
        GlassStyle.MATTE -> R.string.glass_style_matte
        GlassStyle.SOLID -> R.string.glass_style_solid
    }

private val GlassStyle.summary: Int
    get() = when (this) {
        GlassStyle.LIQUID -> R.string.glass_style_liquid_summary
        GlassStyle.FROSTED -> R.string.glass_style_frosted_summary
        GlassStyle.MATTE -> R.string.glass_style_matte_summary
        GlassStyle.SOLID -> R.string.glass_style_solid_summary
    }

@Composable
internal fun NavBarLayoutPicker(selected: NavBarLayout, enabled: Boolean, onSelect: (NavBarLayout) -> Unit) {
    Text(stringResource(R.string.pref_navbar_layout_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NavBarLayout.entries.forEach { layout ->
            val isSelected = layout == selected
            val c = MaterialTheme.colorScheme
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) c.primaryContainer else c.surfaceContainerHigh,
                border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) c.primary else c.outlineVariant),
                modifier = Modifier
                    .weight(1f)
                    .selectable(isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(layout) },
            ) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                    ) { drawLayoutPreview(layout, c.onSurface, c.primary) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(layout.title),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLayoutPreview(layout: NavBarLayout, glass: Color, accent: Color) {
    val h = size.height * 0.7f
    val top = (size.height - h) / 2f
    val hasBubble = layout != NavBarLayout.THREE_TABS
    val gap = h * 0.3f
    val pillWidth = if (hasBubble) size.width - h - gap else size.width
    drawRoundRect(glass.copy(alpha = 0.18f), Offset(0f, top), Size(pillWidth, h), CornerRadius(h / 2f))
    val slot = pillWidth / 3f
    repeat(3) { i ->
        val center = Offset(slot * i + slot / 2f, size.height / 2f)
        if (i == 0) drawRoundRect(accent.copy(alpha = 0.3f), Offset(slot * i + 3f, top + 3f), Size(slot - 6f, h - 6f), CornerRadius((h - 6f) / 2f))
        drawCircle(if (i == 0) accent else glass.copy(alpha = 0.8f), radius = h * 0.16f, center = center)
    }
    if (hasBubble) {
        val left = pillWidth + gap
        drawCircle(glass.copy(alpha = 0.18f), radius = h / 2f, center = Offset(left + h / 2f, size.height / 2f))
        drawCircle(glass.copy(alpha = 0.8f), radius = h * 0.16f, center = Offset(left + h / 2f, size.height / 2f))
    }
}

@Composable
internal fun GlassStylePicker(selected: GlassStyle, enabled: Boolean, onSelect: (GlassStyle) -> Unit) {
    Text(stringResource(R.string.pref_glass_style_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        GlassStyle.entries.forEachIndexed { index, style ->
            SegmentedButton(
                selected = style == selected,
                onClick = { onSelect(style) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, GlassStyle.entries.size),
                icon = {},
            ) { Text(stringResource(style.title), maxLines = 1, softWrap = false) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(selected.summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
