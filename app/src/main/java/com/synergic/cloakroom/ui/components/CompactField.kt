package com.synergic.cloakroom.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * An outlined text field about 44 dp tall (the standard one is 56 dp), used on the Check-in form
 * and the Check-out search. [error] shows in red under the field, else [hint].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactField(
    value: String,
    onChange: (String) -> Unit,
    label: String?,
    error: String?,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    hint: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val isError = error != null
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        readOnly = readOnly,
        singleLine = true,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboard,
        interactionSource = interaction,
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = inner,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interaction,
            isError = isError,
            label = label?.let { { Text(it) } },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            supportingText = (error ?: hint)?.let { { Text(it) } },
            colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            container = {
                OutlinedTextFieldDefaults.ContainerBox(enabled = true, isError = isError, interactionSource = interaction, colors = colors, shape = shape)
            },
        )
    }
}
