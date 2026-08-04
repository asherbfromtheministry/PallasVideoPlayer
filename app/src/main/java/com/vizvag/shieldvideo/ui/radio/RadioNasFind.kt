package com.vizvag.shieldvideo.ui.radio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vizvag.shieldvideo.music.data.local.AlbumWithArtist
import com.vizvag.shieldvideo.music.data.local.ArtistEntity
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
import com.vizvag.shieldvideo.ui.theme.AudioSurface
import com.vizvag.shieldvideo.ui.theme.AudioText
import com.vizvag.shieldvideo.ui.theme.AudioTextMuted
import com.vizvag.shieldvideo.ui.theme.PallasFontFamily
import com.vizvag.shieldvideo.ui.theme.rememberTvFeedback
import kotlinx.coroutines.delay

/** Find-on-NAS query opened from Radio now-playing / recently played. */
sealed class RadioNasFind {
    abstract val query: String

    data class Artist(override val query: String) : RadioNasFind()
    data class Track(override val query: String) : RadioNasFind()
}

private data class NasFindRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onPlay: () -> Unit,
)

/**
 * Overlay on the Radio stage: search the local music index while radio keeps playing.
 * Choosing a result calls [onPlay…] then the caller navigates to Music.
 */
@Composable
fun RadioNasFindPanel(
    find: RadioNasFind,
    artists: List<ArtistEntity>,
    albums: List<AlbumWithArtist>,
    tracks: List<TrackEntity>,
    libraryReady: Boolean,
    accent: Color,
    onClose: () -> Unit,
    onPlayAlbum: (albumId: String) -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onPlayArtistTracks: (List<TrackEntity>) -> Unit,
    onQueryChange: (RadioNasFind) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val closeFocus = remember { FocusRequester() }
    val queryFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    var activeQuery by remember(find) { mutableStateOf(find.query) }
    var showQueryEditor by remember { mutableStateOf(false) }
    var findKind by remember(find) {
        mutableStateOf(
            when (find) {
                is RadioNasFind.Artist -> NasFindKind.Artist
                is RadioNasFind.Track -> NasFindKind.Track
            },
        )
    }
    val q = activeQuery.trim()

    fun applySearch(text: String, kind: NasFindKind = findKind) {
        val trimmed = text.trim()
        activeQuery = trimmed
        findKind = kind
        val next = when (kind) {
            NasFindKind.Artist -> RadioNasFind.Artist(trimmed)
            NasFindKind.Track -> RadioNasFind.Track(trimmed)
        }
        onQueryChange(next)
    }

    val rows = remember(findKind, artists, albums, tracks, q) {
        when (findKind) {
            NasFindKind.Artist -> artistFindRows(
                query = q,
                artists = artists,
                albums = albums,
                tracks = tracks,
                onPlayAlbum = onPlayAlbum,
                onPlayArtistTracks = onPlayArtistTracks,
            )
            NasFindKind.Track -> trackFindRows(
                query = q,
                tracks = tracks,
                onPlayTrack = onPlayTrack,
            )
        }
    }

    LaunchedEffect(find) {
        // Fresh open from artist/track click — focus the query so OK edits immediately.
        delay(60)
        runCatching { queryFocus.requestFocus() }
    }

    LaunchedEffect(q, findKind, rows.size, showQueryEditor) {
        if (showQueryEditor) return@LaunchedEffect
        delay(60)
        if (rows.isNotEmpty()) {
            runCatching { firstRowFocus.requestFocus() }
        }
    }

    // Separate Dialog window so D-pad focus cannot escape to rail / radio controls behind.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        BackHandler {
            if (showQueryEditor) {
                showQueryEditor = false
            } else {
                onClose()
            }
        }
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(AudioSurface.copy(alpha = 0.94f))
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.08f),
                        ),
                    ),
                    shape = RoundedCornerShape(0.dp),
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NasFindIconButton(
                onClick = onClose,
                focusRequester = closeFocus,
                contentDescription = "Back to radio",
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "FIND ON NAS",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = PallasFontFamily,
                )
                Text(
                    text = "Edit query · OK on field opens keyboard",
                    color = AudioTextMuted,
                    fontSize = 12.sp,
                    fontFamily = PallasFontFamily,
                )
            }
            NasFindIconButton(onClick = onClose, contentDescription = "Close") {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            NasFindKindChip(
                label = "Artist",
                selected = findKind == NasFindKind.Artist,
                accent = accent,
                onClick = { applySearch(activeQuery, NasFindKind.Artist) },
            )
            NasFindKindChip(
                label = "Song",
                selected = findKind == NasFindKind.Track,
                accent = accent,
                onClick = { applySearch(activeQuery, NasFindKind.Track) },
            )
        }

        Spacer(Modifier.height(10.dp))
        NasFindQueryField(
            value = activeQuery,
            accent = accent,
            focusRequester = queryFocus,
            onClick = { showQueryEditor = true },
        )

        Text(
            text = "Radio keeps playing · OK on a result opens Music · Back returns here",
            color = AudioTextMuted,
            fontSize = 12.sp,
            fontFamily = PallasFontFamily,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )

        when {
            !libraryReady && rows.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Loading music library…",
                            color = AudioTextMuted,
                            fontSize = 14.sp,
                            fontFamily = PallasFontFamily,
                        )
                    }
                }
            }
            q.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Enter a search, then pick a result",
                        color = AudioTextMuted,
                        fontSize = 15.sp,
                        fontFamily = PallasFontFamily,
                    )
                }
            }
            rows.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (findKind) {
                            NasFindKind.Artist -> "No artists or albums matching “$q”"
                            NasFindKind.Track -> "No songs named “$q”"
                        },
                        color = AudioTextMuted,
                        fontSize = 15.sp,
                        fontFamily = PallasFontFamily,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        NasFindResultRow(
                            title = row.title,
                            subtitle = row.subtitle,
                            icon = row.icon,
                            accent = accent,
                            focusRequester = if (index == 0) firstRowFocus else null,
                            onClick = row.onPlay,
                        )
                    }
                }
            }
        }
        }

        if (showQueryEditor) {
            NasFindQueryEditorDialog(
                initialValue = activeQuery,
                accent = accent,
                onDismiss = { showQueryEditor = false },
                onConfirm = { value ->
                    applySearch(value)
                    showQueryEditor = false
                },
            )
        }
    }
}

private enum class NasFindKind { Artist, Track }

private fun artistFindRows(
    query: String,
    artists: List<ArtistEntity>,
    albums: List<AlbumWithArtist>,
    tracks: List<TrackEntity>,
    onPlayAlbum: (String) -> Unit,
    onPlayArtistTracks: (List<TrackEntity>) -> Unit,
): List<NasFindRow> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<NasFindRow>()
    val matchingArtists = artistsWithPerformers(artists, tracks).filter {
        it.name.equals(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
    }
    for (artist in matchingArtists.take(8)) {
        val artistTracks = tracks.filter { trackMatchesPerformer(it, artist.name) }
        if (artistTracks.isEmpty()) continue
        out += NasFindRow(
            id = "artist:${artist.id}",
            title = artist.name,
            subtitle = "Play all · ${artistTracks.size} tracks",
            icon = Icons.Filled.Person,
            onPlay = { onPlayArtistTracks(artistTracks) },
        )
    }
    val albumHits = albumsForPerformer(albums, tracks, query)
    for (album in albumHits.take(40)) {
        out += NasFindRow(
            id = "album:${album.albumId}:${album.folderPath}",
            title = album.title,
            subtitle = "${album.artistName} · ${album.trackCount} tracks" +
                (album.year?.let { " · $it" } ?: ""),
            icon = Icons.Filled.Album,
            onPlay = { onPlayAlbum(album.albumId) },
        )
    }
    return out.distinctBy { it.id }
}

private fun trackFindRows(
    query: String,
    tracks: List<TrackEntity>,
    onPlayTrack: (TrackEntity) -> Unit,
): List<NasFindRow> {
    if (query.isEmpty()) return emptyList()
    return songsMatchingTitle(tracks, query).take(50).map { track ->
        NasFindRow(
            id = "track:${track.id}",
            title = MetadataResolver.fixTagText(track.title).trim().ifBlank { track.title },
            subtitle = listOfNotNull(
                track.artistName.takeIf { it.isNotBlank() },
                track.albumTitle.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            icon = Icons.Filled.AudioFile,
            onPlay = { onPlayTrack(track) },
        )
    }
}

private fun songsMatchingTitle(tracks: List<TrackEntity>, query: String): List<TrackEntity> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return emptyList()
    val exact = mutableListOf<TrackEntity>()
    val starts = mutableListOf<TrackEntity>()
    val contains = mutableListOf<TrackEntity>()
    for (t in tracks) {
        val title = MetadataResolver.fixTagText(t.title).trim()
        when {
            title.equals(wanted, ignoreCase = true) -> exact += t
            title.startsWith(wanted, ignoreCase = true) -> starts += t
            title.contains(wanted, ignoreCase = true) -> contains += t
        }
    }
    return (exact + starts + contains).distinctBy { it.id }
}

private fun artistsWithPerformers(
    artists: List<ArtistEntity>,
    tracks: List<TrackEntity>,
): List<ArtistEntity> {
    val result = LinkedHashMap<String, ArtistEntity>()
    for (a in artists) {
        val name = a.name.trim()
        if (name.isEmpty() || isVariousArtistsName(name)) continue
        result[name.lowercase()] = a
    }
    for ((key, group) in tracks.groupBy { it.artistName.trim().lowercase() }) {
        if (key.isEmpty() || key in result) continue
        val sample = group.first().artistName.trim()
        if (sample.isEmpty() ||
            isVariousArtistsName(sample) ||
            MetadataResolver.isPlaceholderArtist(sample)
        ) {
            continue
        }
        val albumKeys = group.map {
            it.nasPath.replace('\\', '/').substringBeforeLast('/') to
                it.albumTitle.trim().lowercase()
        }.distinct()
        result[key] = ArtistEntity(
            id = "performer:$key",
            name = sample,
            sortKey = key,
            albumCount = albumKeys.size,
            trackCount = group.size,
        )
    }
    return result.values.sortedBy { it.sortKey }
}

private fun albumsForPerformer(
    albums: List<AlbumWithArtist>,
    tracks: List<TrackEntity>,
    performer: String,
): List<AlbumWithArtist> {
    val wanted = performer.trim()
    if (wanted.isEmpty()) return emptyList()

    fun folderOf(path: String): String =
        path.replace('\\', '/').substringBeforeLast('/').trimEnd('/').lowercase()

    return tracks
        .asSequence()
        .filter { trackMatchesPerformer(it, wanted) }
        .groupBy { folderOf(it.nasPath) to it.albumTitle.trim().lowercase() }
        .filterKeys { (folder, title) -> folder.isNotBlank() && title.isNotBlank() }
        .map { (key, group) ->
            val (folder, _) = key
            val sample = group.first()
            val title = sample.albumTitle.trim()
            val existing = albums
                .filter { album ->
                    album.title.equals(title, ignoreCase = true) &&
                        (
                            album.folderPath?.let { folderOf(it) } == folder ||
                                group.any { it.albumId == album.albumId }
                            )
                }
                .maxByOrNull { it.trackCount }
            AlbumWithArtist(
                albumId = existing?.albumId ?: sample.albumId,
                title = existing?.title ?: title,
                artistId = existing?.artistId ?: sample.artistId,
                artistName = wanted,
                year = existing?.year ?: group.mapNotNull { it.year }.maxOrNull(),
                genre = existing?.genre ?: sample.genre,
                coverPath = existing?.coverPath,
                trackCount = group.size,
                folderPath = existing?.folderPath
                    ?: sample.nasPath.replace('\\', '/').substringBeforeLast('/'),
            )
        }
        .distinctBy { folderOf(it.folderPath.orEmpty()) + "\u0000" + it.title.lowercase() }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.year ?: 0 }))
}

private fun trackMatchesPerformer(t: TrackEntity, wanted: String): Boolean {
    val a = t.artistName.trim()
    if (a.equals(wanted, ignoreCase = true)) return true
    if (a.startsWith(wanted, ignoreCase = true)) return true
    if (a.contains(wanted, ignoreCase = true) && !isVariousArtistsName(a)) return true
    return false
}

private fun isVariousArtistsName(name: String): Boolean {
    val n = name.trim().lowercase()
    return n in setOf("various artists", "various artist", "various", "va", "v.a.", "v.a") ||
        n.startsWith("various artist")
}

@Composable
private fun NasFindQueryField(
    value: String,
    accent: Color,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else accent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (focused) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f),
            )
            .clickable(role = Role.Button) {
                feedback.click()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = value.ifBlank { "Type a search…" },
            color = if (value.isBlank()) AudioTextMuted else AudioText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = PallasFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NasFindKindChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(
                width = if (focused || selected) 2.dp else 1.dp,
                color = when {
                    focused -> Color.White
                    selected -> accent
                    else -> Color.White.copy(alpha = 0.2f)
                },
                shape = RoundedCornerShape(20.dp),
            )
            .background(
                when {
                    selected -> accent.copy(alpha = 0.35f)
                    focused -> Color.White.copy(alpha = 0.1f)
                    else -> Color.Transparent
                },
            )
            .clickable(role = Role.Button) {
                feedback.click()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = AudioText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = PallasFontFamily,
        )
    }
}

@Composable
private fun NasFindQueryEditorDialog(
    initialValue: String,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(initialValue) { mutableStateOf(initialValue) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141418))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                "Search NAS library",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PallasFontFamily,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = {
                    Text(
                        "Artist or song title…",
                        color = AudioTextMuted,
                        fontSize = 16.sp,
                        fontFamily = PallasFontFamily,
                    )
                },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = PallasFontFamily,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onConfirm(draft) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = accent,
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                ),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NasFindDialogButton(label = "Cancel", accent = accent, onClick = onDismiss)
                NasFindDialogButton(
                    label = "Search",
                    accent = accent,
                    emphasized = true,
                    onClick = { onConfirm(draft) },
                )
            }
        }
    }
}

@Composable
private fun NasFindDialogButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Color.White
                    emphasized -> accent
                    else -> Color.White.copy(alpha = 0.22f)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                when {
                    emphasized && focused -> accent.copy(alpha = 0.55f)
                    emphasized -> accent.copy(alpha = 0.35f)
                    focused -> Color.White.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .clickable(role = Role.Button) {
                feedback.click()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = PallasFontFamily,
        )
    }
}

@Composable
private fun NasFindResultRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else accent.copy(alpha = 0.28f),
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (focused) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.04f),
            )
            .clickable(role = Role.Button) {
                feedback.click()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = AudioText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PallasFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = AudioTextMuted,
                    fontSize = 13.sp,
                    fontFamily = PallasFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NasFindIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val feedback = rememberTvFeedback()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp),
            )
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Button) {
                feedback.click()
                onClick()
            },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}
