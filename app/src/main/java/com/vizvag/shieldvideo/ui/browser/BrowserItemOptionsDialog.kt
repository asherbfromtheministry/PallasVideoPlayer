package com.vizvag.shieldvideo.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.ui.theme.Accent
import com.vizvag.shieldvideo.ui.theme.AppBackground
import com.vizvag.shieldvideo.ui.theme.TextMuted
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay

@Composable
fun BrowserItemOptionsDialog(
    item: MediaCardItem,
    confirmingDelete: Boolean,
    onAssignMetadata: () -> Unit,
    onClearIncludingContents: () -> Unit,
    onClearFolderOnly: () -> Unit,
    onClearOrRestoreMetadata: () -> Unit,
    onExtractArchive: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val feedback = rememberTvFeedback()
    var armed by remember { mutableStateOf(false) }
    val isDir = item.entry.isDirectory
    val isArchive = !isDir && NasPaths.isArchiveFile(item.entry.name)
    val hasTraktArt = !item.posterUrl.isNullOrBlank() || !item.fanartUrl.isNullOrBlank()

    LaunchedEffect(item.entry.path, confirmingDelete) {
        armed = false
        delay(280)
        armed = true
        delay(40)
        runCatching { firstFocus.requestFocus() }
    }

    fun run(action: () -> Unit) {
        if (armed) action()
    }

    BackHandler(onBack = {
        if (confirmingDelete) onCancelDelete() else onDismiss()
    })

    Dialog(
        onDismissRequest = {
            if (confirmingDelete) onCancelDelete() else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.58f)
                .clip(RoundedCornerShape(20.dp))
                .background(AppBackground)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                text = if (confirmingDelete) {
                    if (isDir) "Delete folder?" else "Delete file?"
                } else {
                    item.displayTitle
                },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (confirmingDelete) {
                    if (isDir) {
                        "Permanently deletes “${item.entry.name}” and everything inside from the NAS."
                    } else {
                        "Permanently deletes “${item.entry.name}” from the NAS."
                    }
                } else {
                    item.entry.name
                },
                color = TextMuted,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (confirmingDelete) {
                    ItemOptionRow(
                        label = if (isDir) "Delete folder" else "Delete file",
                        subtitle = "This cannot be undone",
                        danger = true,
                        requestInitialFocus = true,
                        focusRequester = firstFocus,
                        onClick = {
                            feedback.click()
                            run(onConfirmDelete)
                        },
                    )
                    ItemOptionRow(
                        label = "Keep it",
                        onClick = {
                            feedback.click()
                            run(onCancelDelete)
                        },
                    )
                } else {
                    if (isDir) {
                        if (hasTraktArt && !item.metadataCleared) {
                            ItemOptionRow(
                                label = "Clear metadata — folder and everything inside",
                                subtitle = "Clears nested subfolders and files too",
                                requestInitialFocus = true,
                                focusRequester = firstFocus,
                                onClick = {
                                    feedback.click()
                                    run(onClearIncludingContents)
                                },
                            )
                            ItemOptionRow(
                                label = "Clear metadata — this folder only",
                                subtitle = "Nested folders and files keep their metadata",
                                onClick = {
                                    feedback.click()
                                    run(onClearFolderOnly)
                                },
                            )
                        } else {
                            ItemOptionRow(
                                label = "Assign TV / movie…",
                                requestInitialFocus = true,
                                focusRequester = firstFocus,
                                onClick = {
                                    feedback.click()
                                    run(onAssignMetadata)
                                },
                            )
                        }
                    } else if (isArchive) {
                        ItemOptionRow(
                            label = "Extract archive…",
                            requestInitialFocus = true,
                            focusRequester = firstFocus,
                            onClick = {
                                feedback.click()
                                run(onExtractArchive)
                            },
                        )
                    } else {
                        ItemOptionRow(
                            label = if (item.metadataCleared) {
                                "Restore metadata lookup"
                            } else {
                                "Clear TV / movie metadata"
                            },
                            requestInitialFocus = true,
                            focusRequester = firstFocus,
                            onClick = {
                                feedback.click()
                                run(onClearOrRestoreMetadata)
                            },
                        )
                    }
                    ItemOptionRow(
                        label = if (isDir) "Delete folder…" else "Delete file…",
                        subtitle = "Remove from NAS permanently",
                        danger = true,
                        onClick = {
                            feedback.click()
                            run(onRequestDelete)
                        },
                    )
                    ItemOptionRow(
                        label = "Cancel",
                        onClick = {
                            feedback.click()
                            run(onDismiss)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemOptionRow(
    label: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    danger: Boolean = false,
    requestInitialFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val localFocus = remember { FocusRequester() }
    val requester = focusRequester ?: localFocus
    var focused by remember { mutableStateOf(false) }
    val feedback = rememberTvFeedback()
    val interaction = remember { MutableInteractionSource() }

    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            delay(60)
            runCatching { requester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused && danger -> Color(0xFFFF5252).copy(alpha = 0.22f)
                    focused -> Color.White.copy(alpha = 0.10f)
                    else -> Color.White.copy(alpha = 0.04f)
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused && danger -> Color(0xFFFF8A80)
                    focused -> Accent
                    else -> Color.White.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .focusRequester(requester)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) feedback.focus()
            }
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (danger) Color(0xFFFF8A80) else Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
