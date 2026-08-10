package com.vizvag.shieldvideo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vizvag.shieldvideo.ui.theme.LocalScreenChrome

@Composable
fun SleepTimerButton(
    active: Boolean,
    label: String?,
    onCycle: () -> Unit,
    enabled: Boolean = true,
) {
    val chrome = LocalScreenChrome.current
    if (!label.isNullOrBlank()) {
        Text(
            text = label,
            color = if (active) chrome.accent else chrome.muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
    IconActionButton(selected = active, onClick = onCycle, enabled = enabled) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = "Sleep timer",
            tint = if (active) chrome.accent else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun SleepTimerCustomDialog(
    initialMinutes: Int,
    onConfirmMinutes: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chrome = LocalScreenChrome.current
    val focusRequester = remember { FocusRequester() }
    var value by remember(initialMinutes) {
        mutableStateOf(
            TextFieldValue(
                text = initialMinutes.coerceIn(1, 999).toString(),
                selection = TextRange(initialMinutes.coerceIn(1, 999).toString().length),
            ),
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = chrome.accent)
        },
        title = { Text("Custom sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Minutes (1–999)", color = chrome.muted, fontSize = 13.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = it.text.filter { ch -> ch.isDigit() }.take(3)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = chrome.accent,
                        cursorColor = chrome.accent,
                        focusedLabelColor = chrome.accent,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mins = value.text.toIntOrNull()?.coerceIn(1, 999) ?: return@TextButton
                onConfirmMinutes(mins)
                onDismiss()
            }) {
                Text("Start", color = chrome.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    onClear()
                    onDismiss()
                }) {
                    Text("Off", color = chrome.muted)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = chrome.muted)
                }
            }
        },
    )
}
