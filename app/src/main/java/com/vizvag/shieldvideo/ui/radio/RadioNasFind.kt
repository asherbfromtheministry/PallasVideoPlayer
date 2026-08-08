package com.vizvag.shieldvideo.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.music.data.SearchResults
import com.vizvag.shieldvideo.music.data.local.AlbumWithArtist
import com.vizvag.shieldvideo.music.data.local.ArtistEntity
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
import com.vizvag.shieldvideo.ui.components.glassInteract
import com.vizvag.shieldvideo.ui.theme.AudioTextMuted
import com.vizvag.shieldvideo.ui.theme.CardSurface
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import kotlinx.coroutines.delay

/** Find-on-NAS query opened from Radio now-playing / recently played. */
sealed class RadioNasFind {
    abstract val query: String

    data class Artist(override val query: String) : RadioNasFind()
    data class Track(override val query: String) : RadioNasFind()
}

/**
 * Same three-column search UI as Music. Radio keeps playing until a result is chosen
 * ([onPlayArtist] / [onPlayAlbum] / [onPlayTrack]); Back/Close returns to Radio.
 */
@Composable
fun RadioNasFindPanel(
    find: RadioNasFind,
    search: suspend (String) -> SearchResults,
    accent: Color,
    onClose: () -> Unit,
    onPlayArtist: (ArtistEntity) -> Unit,
    onPlayAlbum: (AlbumWithArtist) -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onQueryChange: (RadioNasFind) -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val closeFocus = remember { FocusRequester() }
    val queryBarFocus = remember { FocusRequester() }
    val editFieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    var query by remember(find) { mutableStateOf(find.query) }
    var editingQuery by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(SearchResults()) }
    var searching by remember { mutableStateOf(false) }

    fun publishQuery(value: String) {
        query = value
        val trimmed = value.trim()
        onQueryChange(
            when (find) {
                is RadioNasFind.Artist -> RadioNasFind.Artist(trimmed)
                is RadioNasFind.Track -> RadioNasFind.Track(trimmed)
            },
        )
    }

    fun stopEditing() {
        editingQuery = false
        keyboard?.hide()
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = SearchResults()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(280)
        results = runCatching { search(q) }.getOrDefault(SearchResults())
        searching = false
    }

    val hasResults = results.artists.isNotEmpty() ||
        results.albums.isNotEmpty() ||
        results.tracks.isNotEmpty()

    LaunchedEffect(Unit) {
        keyboard?.hide()
    }

    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    LaunchedEffect(editingQuery) {
        if (editingQuery) {
            delay(48)
            runCatching { editFieldFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    LaunchedEffect(query, hasResults, editingQuery, searching) {
        if (editingQuery || searching) return@LaunchedEffect
        keyboard?.hide()
        delay(48)
        when {
            query.isNotBlank() && hasResults -> runCatching { firstResultFocus.requestFocus() }
            else -> runCatching { queryBarFocus.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        BackHandler {
            if (editingQuery) {
                stopEditing()
            } else {
                onClose()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(0.92f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardSurface)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 22.dp, vertical = 18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (editingQuery) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { publishQuery(it) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(editFieldFocus),
                            label = { Text("Search artists, albums, songs") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { stopEditing() }),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 16.sp,
                                fontFamily = PallasFontFamily,
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accent,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                                focusedLabelColor = accent,
                                unfocusedLabelColor = AudioTextMuted,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = accent,
                            ),
                        )
                    } else {
                        RadioSearchQueryBar(
                            query = query,
                            accent = accent,
                            modifier = Modifier.weight(1f),
                            focusRequester = queryBarFocus,
                            onActivate = { editingQuery = true },
                        )
                    }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.focusRequester(closeFocus),
                    ) {
                        Text("Close", color = accent)
                    }
                }
                Text(
                    text = "Radio keeps playing until you pick a NAS result",
                    color = AudioTextMuted,
                    fontSize = 12.sp,
                    fontFamily = PallasFontFamily,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )

                val blank = query.isBlank()
                val empty = !blank && !searching && !hasResults

                when {
                    searching && !hasResults -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = accent, modifier = Modifier.size(36.dp))
                        }
                    }
                    blank -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "OK on the search bar to type",
                                color = AudioTextMuted,
                                fontSize = 16.sp,
                                fontFamily = PallasFontFamily,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    empty -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No matches for “$query”",
                                color = AudioTextMuted,
                                fontSize = 16.sp,
                                fontFamily = PallasFontFamily,
                            )
                        }
                    }
                    else -> {
                        val firstArtist = results.artists.isNotEmpty()
                        val firstAlbum = !firstArtist && results.albums.isNotEmpty()
                        val firstTrack = !firstArtist && !firstAlbum && results.tracks.isNotEmpty()
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            RadioSearchColumn(
                                title = "Artists",
                                count = results.artists.size,
                                icon = Icons.Filled.Person,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            ) {
                                itemsIndexed(results.artists, key = { _, it -> it.id }) { index, artist ->
                                    RadioSearchResultRow(
                                        title = artist.name,
                                        subtitle = buildString {
                                            if (artist.albumCount > 0) {
                                                append("${artist.albumCount} album")
                                                if (artist.albumCount != 1) append('s')
                                            }
                                            if (artist.trackCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append("${artist.trackCount} track")
                                                if (artist.trackCount != 1) append('s')
                                            }
                                        }.ifBlank { null },
                                        onClick = { onPlayArtist(artist) },
                                        focusRequester = if (firstArtist && index == 0) {
                                            firstResultFocus
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            RadioSearchColumn(
                                title = "Albums",
                                count = results.albums.size,
                                icon = Icons.Filled.Album,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            ) {
                                itemsIndexed(
                                    results.albums,
                                    key = { _, it -> it.albumId + "\u0000" + it.artistName },
                                ) { index, album ->
                                    RadioSearchResultRow(
                                        title = album.title,
                                        subtitle = buildString {
                                            append(
                                                results.albumArtists[album.albumId]
                                                    ?: album.artistName,
                                            )
                                            album.year?.let { append(" · $it") }
                                        },
                                        onClick = { onPlayAlbum(album) },
                                        focusRequester = if (firstAlbum && index == 0) {
                                            firstResultFocus
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            RadioSearchColumn(
                                title = "Songs",
                                count = results.tracks.size,
                                icon = Icons.Filled.MusicNote,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                            ) {
                                itemsIndexed(results.tracks, key = { _, it -> it.id }) { index, track ->
                                    RadioSearchResultRow(
                                        title = MetadataResolver.fixTagText(track.title)
                                            .trim()
                                            .ifBlank { track.title },
                                        subtitle = buildString {
                                            append(track.artistName)
                                            if (track.albumTitle.isNotBlank()) {
                                                append(" · ")
                                                append(track.albumTitle)
                                            }
                                        },
                                        onClick = { onPlayTrack(track) },
                                        focusRequester = if (firstTrack && index == 0) {
                                            firstResultFocus
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioSearchQueryBar(
    query: String,
    accent: Color,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val label = query.ifBlank { "Search artists, albums, songs" }
    val textColor = if (query.isBlank()) AudioTextMuted else Color.White
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .glassInteract(
                focused = focused,
                selected = false,
                idleSurface = Color.White.copy(alpha = 0.04f),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onActivate)
            .onPreviewKeyEvent { event ->
                val isSelect = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isSelect && event.type == KeyEventType.KeyUp) {
                    onActivate()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = if (focused) accent else AudioTextMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 16.sp,
            fontFamily = PallasFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (query.isNotBlank()) {
            Text(
                text = "OK to edit",
                color = AudioTextMuted.copy(alpha = if (focused) 0.9f else 0.55f),
                fontSize = 11.sp,
                fontFamily = PallasFontFamily,
            )
        }
    }
}

@Composable
private fun RadioSearchColumn(
    title: String,
    count: Int,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                color = AudioTextMuted,
                fontSize = 13.sp,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (count == 0) {
            Text(
                text = "No matches",
                color = AudioTextMuted.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RadioSearchResultRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .glassInteract(
                focused = focused,
                selected = false,
                idleSurface = Color.Transparent,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
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
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = AudioTextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
