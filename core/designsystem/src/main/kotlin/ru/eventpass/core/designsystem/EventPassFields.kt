package ru.eventpass.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    error: String? = null,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions: KeyboardActions? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: EventPassGlyph? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val outline = when {
        error != null -> EventPassColors.danger
        focused -> EventPassColors.accent
        else -> Color.Transparent
    }
    Column(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else EventPassColors.DisabledAlpha),
        verticalArrangement = Arrangement.spacedBy(EventPassDimens.labelGap),
    ) {
        Text(label, style = EventPassTypography.label, color = EventPassColors.textSecondary)
        BasicTextField(
            value, onValueChange,
            modifier.fillMaxWidth().heightIn(min = EventPassDimens.touch).semantics {
                contentDescription = label
                if (error != null) error(error)
            },
            enabled = enabled,
            singleLine = singleLine,
            textStyle = EventPassTypography.body.copy(color = EventPassColors.textPrimary),
            cursorBrush = SolidColor(EventPassColors.accentLight),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions ?: KeyboardActions(onDone = { keyboard?.hide() }, onSearch = { keyboard?.hide() }),
            visualTransformation = visualTransformation,
            interactionSource = interactions,
            decorationBox = { inner ->
                Row(
                    Modifier.padding(vertical = (EventPassDimens.touch - EventPassDimens.buttonHeight) / 2)
                        .background(EventPassColors.surface, EventPassShapes.control)
                        .border(EventPassDimens.border, outline, EventPassShapes.control)
                        .heightIn(min = EventPassDimens.buttonHeight)
                        .padding(horizontal = EventPassDimens.fieldHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(EventPassDimens.iconGap),
                ) {
                    leadingIcon?.let { EventPassIcon(it, null, tint = EventPassColors.muted) }
                    Box(Modifier.weight(1f).padding(vertical = EventPassDimens.small)) {
                        if (value.isEmpty()) Text(placeholder, style = EventPassTypography.body, color = EventPassColors.muted)
                        inner()
                    }
                    trailingContent?.invoke()
                }
            },
        )
        (error ?: supportingText)?.let {
            Text(it, style = EventPassTypography.hint, color = if (error != null) EventPassColors.danger else EventPassColors.muted)
        }
    }
}

@Composable
fun PasswordField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    error: String? = null, supportingText: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    FormField(
        value, onValueChange, label, modifier, enabled = enabled, error = error, supportingText = supportingText,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingContent = {
            IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.size(EventPassDimens.touch)) {
                EventPassIcon(
                    if (visible) EventPassGlyph.Hidden else EventPassGlyph.Visible,
                    stringResource(if (visible) R.string.ep_hide_password else R.string.ep_show_password),
                )
            }
        },
    )
}

@Composable
fun SearchField(
    value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, placeholder: String = "", enabled: Boolean = true,
    label: String = stringResource(R.string.ep_search),
) {
    FormField(value, onValueChange, label, modifier, placeholder, enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), leadingIcon = EventPassGlyph.Search)
}
