package ru.eventpass.core.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    readOnly: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    val message = error?.takeIf(String::isNotBlank) ?: supportingText?.takeIf(String::isNotBlank)
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = label
            if (!error.isNullOrBlank()) error(error)
        },
        label = { Text(label, style = EventPassTypography.label) },
        placeholder = if(placeholder.isBlank()) null else ({ Text(placeholder, style = EventPassTypography.body) }),
        enabled = enabled, readOnly = readOnly, isError = !error.isNullOrBlank(), singleLine = singleLine,
        textStyle = EventPassTypography.body,
        supportingText = if(message == null) null else ({ FieldMessage(error, supportingText) }),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions ?: KeyboardActions(onDone = { keyboard?.hide() }, onSearch = { keyboard?.hide() }),
        visualTransformation = visualTransformation, interactionSource = interactions,
        leadingIcon = leadingIcon?.let { icon -> { EventPassIcon(icon, null, tint = EventPassColors.muted) } },
        trailingIcon = trailingContent,
        shape = EventPassShapes.control,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = EventPassColors.textPrimary, unfocusedTextColor = EventPassColors.textPrimary,
            disabledTextColor = EventPassColors.textPrimary.copy(alpha = EventPassColors.DisabledAlpha),
            errorTextColor = EventPassColors.textPrimary,
            focusedContainerColor = EventPassColors.surface, unfocusedContainerColor = EventPassColors.surface,
            disabledContainerColor = EventPassColors.surface, errorContainerColor = EventPassColors.surface,
            cursorColor = EventPassColors.accentLight, errorCursorColor = EventPassColors.danger,
            focusedBorderColor = EventPassColors.accentLight, unfocusedBorderColor = EventPassColors.raised,
            disabledBorderColor = EventPassColors.raised, errorBorderColor = EventPassColors.danger,
            focusedLabelColor = EventPassColors.accentLight, unfocusedLabelColor = EventPassColors.textSecondary,
            disabledLabelColor = EventPassColors.muted, errorLabelColor = EventPassColors.danger,
            focusedPlaceholderColor = EventPassColors.muted, unfocusedPlaceholderColor = EventPassColors.muted,
            errorPlaceholderColor = EventPassColors.muted,
            focusedSupportingTextColor = EventPassColors.muted, unfocusedSupportingTextColor = EventPassColors.muted,
            errorSupportingTextColor = EventPassColors.danger,
        ),
    )
}

data class SelectOption(val value: String, val title: String, val hint: String? = null)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SelectField(value: String?, options: List<SelectOption>, onSelect: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, error: String? = null,
    placeholder: String = "", supportingText: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded && enabled, onExpandedChange = { if(enabled) expanded = it }, modifier = modifier,
    ) {
        FormField(options.firstOrNull { it.value == value }?.title.orEmpty(), {}, label,
            Modifier.menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            enabled = enabled, error = error, placeholder = placeholder, supportingText = supportingText,
            readOnly = true, trailingContent = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) })
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            containerColor = EventPassColors.raised) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(text = {
                    androidx.compose.foundation.layout.Column {
                        Text(option.title, color = EventPassColors.textPrimary, style = EventPassTypography.body)
                        option.hint?.takeIf(String::isNotBlank)?.let { FieldMessage(supportingText = it) }
                    }
                }, onClick = { expanded = false; onSelect(option.value) })
            }
        }
    }
}

/** A field has one supporting message: its error takes precedence over its ordinary hint. */
@Composable
fun FieldMessage(error: String? = null, supportingText: String? = null, modifier: Modifier = Modifier) {
    val invalid = error?.takeIf(String::isNotBlank)
    val message = invalid ?: supportingText?.takeIf(String::isNotBlank) ?: return
    Text(message, modifier, style = EventPassTypography.hint,
        color = if(invalid != null) EventPassColors.danger else EventPassColors.muted)
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
