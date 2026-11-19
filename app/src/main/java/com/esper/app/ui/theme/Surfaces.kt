package com.esper.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.esper.app.ui.theme.EsperColors.ink
import com.esper.app.ui.theme.EsperColors.inkSoft
import com.esper.app.ui.theme.EsperColors.line
import com.esper.app.ui.theme.EsperColors.paper
import com.esper.app.ui.theme.EsperColors.paperDim
import com.esper.app.ui.theme.EsperColors.turn

/**
 * A surface of §4.1: cut corners, a 1.5 dp outline, and with [foot] a 3 dp solid ink foot
 * below in place of a shadow. [pressed] moves the surface down onto its foot.
 */
@Composable
private fun SurfaceBox(
    shape: Shape,
    fill: Color,
    outline: Color,
    foot: Boolean,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val footDp = if (foot) Spec.FOOT_DP else 0f
    val down = if (foot && pressed) Spec.PRESS_DP else 0f
    Box(
        modifier = modifier
            .padding(top = down.dp, bottom = (footDp - down).dp)
            .drawBehind {
                if (foot) translate(top = (footDp - down).dp.toPx()) { drawOutline(shape.createOutline(size, layoutDirection, this), ink) }
            }
            .background(fill, shape)
            .border(Spec.OUTLINE_DP.dp, outline, shape),
        content = content,
    )
}

/** A card: [paper], cut 8, with a foot. [outline] is [EsperColors.foe] for a warning. */
@Composable
fun EsperCard(
    modifier: Modifier = Modifier,
    outline: Color = ink,
    content: @Composable ColumnScope.() -> Unit,
) {
    SurfaceBox(MaterialTheme.shapes.medium, paper, outline, foot = true, modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spec.GAP_WIDE_DP.dp),
            verticalArrangement = Arrangement.spacedBy(Spec.GAP_DP.dp),
            content = content,
        )
    }
}

/**
 * A button. [primary] is the screen's one gold button, 56 dp; every other is [paper], 48 dp.
 * Pressed it turns [paperDim]; disabled it is [paperDim] with a [line] outline and no foot.
 */
@Composable
fun EsperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        !enabled || pressed -> paperDim
        primary -> turn
        else -> paper
    }
    SurfaceBox(
        shape = MaterialTheme.shapes.small,
        fill = fill,
        outline = if (enabled) ink else line,
        foot = enabled,
        pressed = pressed,
        modifier = modifier.clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = (if (primary) Spec.PRIMARY_BUTTON_DP else Spec.TOUCH_MIN_DP).dp)
                .align(Alignment.Center)
                .padding(horizontal = Spec.SCREEN_PADDING_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) ink else inkSoft)
        }
    }
}

/** A chip: a label with an optional icon on [paper], cut 6. Not pressable, so no foot. */
@Composable
fun EsperChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    SurfaceBox(MaterialTheme.shapes.small, paper, ink, foot = false, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = Spec.GAP_DP.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, color = ink)
        }
    }
}

/** A stat tile: a [paperDim] well, cut 4, label over value. */
@Composable
fun EsperTile(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = Spec.TILE_MIN_DP.dp)
            .background(paperDim, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = Spec.GAP_DP.dp, vertical = 4.dp),
    ) {
        Column {
            Text(label, style = hudStyle(12), color = inkSoft)
            Text(value, style = hudStyle(20), color = ink)
        }
    }
}

/** A bar (XP, job points): [fill] for [fraction] of a [paperDim] track with an ink outline. */
@Composable
fun EsperBar(fraction: Float, fill: Color, modifier: Modifier = Modifier, height: Float = Spec.BAR_DP) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(paperDim)
            .border(Spec.OUTLINE_DP.dp, ink),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(fill))
    }
}

/** A field: a [paperDim] well, cut 6, ink outline. */
@Composable
fun EsperField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = paperDim,
            unfocusedContainerColor = paperDim,
            focusedBorderColor = ink,
            unfocusedBorderColor = ink,
            focusedLabelColor = ink,
            unfocusedLabelColor = inkSoft,
            cursorColor = ink,
        ),
        modifier = modifier,
    )
}
