package com.vizvag.shieldvideo.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.ShieldVideoApp
import kotlinx.coroutines.delay

/** Full-screen black overlay shared by the Black button and sleep timer. */
@Composable
fun AppBlackoutHost() {
    val app = LocalContext.current.applicationContext as ShieldVideoApp
    val active by app.blackout.active.collectAsState()
    if (!active) return

    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    Dialog(
        onDismissRequest = { app.blackout.exit() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BackHandler { app.blackout.exit() }
        LaunchedEffect(Unit) {
            delay(60)
            runCatching { focus.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .focusable(interactionSource = interaction)
                .clickable(
                    indication = null,
                    interactionSource = interaction,
                    role = Role.Button,
                    onClick = { app.blackout.exit() },
                ),
        )
    }
}
