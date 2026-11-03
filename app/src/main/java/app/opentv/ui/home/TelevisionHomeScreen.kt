/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.data.model.*
import app.opentv.data.parser.displayTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Displays customizable television home shelves, featured content and application shortcuts. */
@Composable
fun TelevisionHomeScreen(
    hasSources: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (String, String, String) -> Unit,
    onAddSource: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenApps: () -> Unit,
    vod: VodViewModel = viewModel(),
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val homeStore = remember(context) { HomePreferencesStore(context) }
    val homePreferences by homeStore.preferences.collectAsState()
    var customizeHome by remember { mutableStateOf(false) }
    val presentation = homePreferences.presentation
    val customGroups = app.opentv.ui.channels.rememberCustomGroupShelves { homePreferences.isVisible(HomeShelfOrder.groupKey(it.cloudId)) }
    val groupNames = remember(customGroups) { customGroups.associate { HomeShelfOrder.groupKey(it.first.cloudId) to it.first.name } }
    val order = remember(homePreferences.shelfOrder, groupNames) { HomeShelfOrder.normalize(homePreferences.shelfOrder, groupNames.keys.toList()) }
    val focusedArtwork = remember { mutableStateOf<String?>(null) }
    val tileStyle = remember(presentation) { HomeTileStyle(presentation) { focusedArtwork.value = it } }
    DisposableEffect(homeStore) { onDispose { homeStore.close() } }
    val history = remember { RecentChannels.get(context) }
    val ids by history.ids.collectAsState()
    val recentFlow = remember(ids) { graph.database.channels().observeByIds(ids) }
    val recentRaw by recentFlow.collectAsState(initial = emptyList())
    val recent = remember(ids, recentRaw) { ids.mapNotNull { id -> recentRaw.firstOrNull { it.id == id } } }
    val channels by remember { graph.database.channels().observeVisibleChannels() }.collectAsState(initial = emptyList())
    val favourites by remember { graph.database.channels().observeFavourites() }.collectAsState(initial = emptyList())
    val resume by vod.continueWatching.collectAsState()
    val recommended by vod.recommendedMovies.collectAsState()
    val movies by vod.recentlyAddedMovies.collectAsState()
    val series by vod.recentlyAddedSeries.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val nowFlow = remember(now) { graph.epgRepository.observeNow(now) }
    val programmes by nowFlow.collectAsState(initial = emptyList())
    LaunchedEffect(hasSources) { if (hasSources) vod.loadHomeFeeds() }

    MaterialTheme(colorScheme = if (presentation.background == HomeBackground.DEFAULT) MaterialTheme.colorScheme else app.opentv.ui.theme.DarkScheme) {
        CompositionLocalProvider(LocalHomeTileStyle provides tileStyle) {
            Box(Modifier.fillMaxSize()) {
                HomeBackdrop(presentation.background, focusedArtwork, !customizeHome)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = if (presentation.layout == HomeLayout.CINEMA) 16.dp else 28.dp,
                        vertical = if (presentation.layout == HomeLayout.COMPACT) 16.dp else 22.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(when (presentation.layout) { HomeLayout.COMPACT -> 12.dp; HomeLayout.CINEMA -> 28.dp; else -> 22.dp }),
                ) {
                    item(key = "header") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Make yourself at home.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text("Everything you love, all in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HomeCustomizationAccess(homePreferences, homeStore, groupNames) { customizeHome = it }
                            Spacer(Modifier.width(16.dp))
                            Text("NUVIX", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (presentation.featured != HomeFeatured.DISABLED) item(key = "hero") {
                        HomeEpgGuideCard(presentation, programmes, channels, now, hasSources, onPlayChannel, onOpenGuide, onAddSource)
                    }
                    val movieShelf = recommended.ifEmpty { movies }
                    order.forEach { key ->
                        if (homePreferences.isVisible(key)) when (HomeSection.fromKey(key)) {
                            HomeSection.CONTINUE -> if (resume.isNotEmpty()) item(key = key) {
                                HomeShelf("Continue watching", resume, { it.mediaKey }) { item ->
                                    ContentTile(item.title, item.posterUrl, "Resume watching", item.progress) { onResume(item.mediaKey, item.streamUrl, item.title) }
                                }
                            }
                            HomeSection.RECENT -> if (recent.isNotEmpty()) item(key = key) { ChannelShelf("Recently watched", recent.take(16), programmes, onPlayChannel) }
                            HomeSection.FAVOURITES -> if (favourites.isNotEmpty()) item(key = key) { ChannelShelf("Your favorite channels", favourites.take(24), programmes, onPlayChannel) }
                            HomeSection.ON_NOW -> if (channels.isNotEmpty()) item(key = key) { ChannelShelf("On now", channels.distinctBy { it.groupKey.ifBlank { "id:${it.id}" } }.take(16), programmes, onPlayChannel) }
                            HomeSection.MOVIES -> if (movieShelf.isNotEmpty()) item(key = key) {
                                HomeShelf(if (recommended.isNotEmpty()) "Recommended for you" else "Recently added movies", movieShelf.take(18), { it.id.toString() }) { movie ->
                                    ContentTile(movie.displayTitle, movie.posterUrl, "Movie", size = presentation.movieSize, artworkBackground = true) { onOpenMovie(movie) }
                                }
                            }
                            HomeSection.SERIES -> if (series.isNotEmpty()) item(key = key) {
                                HomeShelf("Recently added shows", series.take(18), { it.id.toString() }) { show ->
                                    ContentTile(show.displayTitle, show.posterUrl, "TV show", size = presentation.seriesSize, artworkBackground = true) { onOpenSeries(show) }
                                }
                            }
                            HomeSection.APPS -> item(key = key) { app.opentv.launcher.FavoriteAppsRow(onOpenApps = onOpenApps) }
                            null -> {
                                val group = customGroups.firstOrNull { HomeShelfOrder.groupKey(it.first.cloudId) == key }
                                if (group != null && group.second.isNotEmpty()) item(key = key) {
                                    ChannelShelf(group.first.name, group.second, programmes, onPlayChannel)
                                }
                            }
                        }
                    }
                    item(key = "navigation") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (presentation.featured == HomeFeatured.DISABLED) OutlinedButton(onClick = onOpenGuide) { Text("Explore guide") }
                            OutlinedButton(onClick = onOpenApps) { Text("Browse installed apps") }
                            OutlinedButton(onClick = onAddSource) { Text("Add another provider") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hero-slot replacement: a compact clickable EPG guide. Each row is a channel with the programme
 * currently on, playing straight from home on activation; the footer expands into the full IPTV
 * guide section.
 */
@Composable
internal fun HomeEpgGuideCard(
    presentation: HomePresentation,
    programmes: List<Programme>,
    channels: List<Channel>,
    now: Long,
    hasSources: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenGuide: () -> Unit,
    onAddSource: () -> Unit,
) {
    // Every channel from the synced M3U source, with the programme on now; channels that have a
    // programme are listed first. The card scrolls, so the whole list is reachable.
    val rows = remember(channels, programmes) {
        val keyOf = { channel: Channel -> channel.epgOverrideId ?: channel.matchedEpgId ?: channel.epgChannelId }
        val byEpgId = programmes.associateBy { it.epgChannelId }
        channels.map { channel -> channel to byEpgId[keyOf(channel)] }
            .sortedByDescending { it.second != null }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(
        Modifier.fillMaxWidth().height(when (presentation.layout) { HomeLayout.COMPACT -> 300.dp; HomeLayout.CINEMA -> 420.dp; else -> 360.dp })
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF142E54), Color(0xFF0D213B), Color(0xFF0A172A)))),
    ) {
        if (channels.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Your television. Your way.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    if (hasSources) "Your provider is being loaded — the channel guide appears here." else "Add your authorized IPTV provider to bring the channel guide here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hasSources) Button(onClick = onAddSource) { Text("Add provider") }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TV GUIDE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text("What's on now", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text(timeFormat.format(Date(now)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(rows, key = { it.first.id }) { (channel, programme) ->
                        EpgGuideRow(channel, programme, now, timeFormat) { onPlayChannel(channel) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = onOpenGuide) {
                        Icon(Icons.Filled.LiveTv, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open the full guide")
                    }
                }
            }
        }
    }
}

/** A single guide row: channel identity, current programme with progress and its airing window. */
@Composable
private fun EpgGuideRow(
    channel: Channel,
    programme: Programme?,
    now: Long,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.015f else 1f, label = "epgGuideRowFocus")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (channel.logoUrl.isNullOrBlank()) {
            Icon(Icons.Filled.LiveTv, null, Modifier.size(38.dp).padding(5.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(channel.logoUrl, channel.shownName, Modifier.size(40.dp), contentScale = ContentScale.Fit)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(channel.shownName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                programme?.title ?: "Live", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (programme != null) {
                LinearProgressIndicator(
                    progress = { programme.progressAt(now) },
                    modifier = Modifier.width(90.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        if (programme != null) {
            Text(
                "${timeFormat.format(Date(programme.startUtcMillis))} – ${timeFormat.format(Date(programme.endUtcMillis))}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Displays an ordered horizontal shelf of available channels. */
@Composable
internal fun ChannelShelf(title: String, channels: List<Channel>, programmes: List<Programme>, onPlay: (Channel) -> Unit) {
    HomeShelf(title, channels, { it.id.toString() }) { channel ->
        val current = programmes.firstOrNull { it.epgChannelId == (channel.epgOverrideId ?: channel.matchedEpgId ?: channel.epgChannelId) }
        ContentTile(channel.shownName, channel.logoUrl, current?.title ?: "Live TV", logo = true, size = LocalHomeTileStyle.current.presentation.channelSize) { onPlay(channel) }
    }
}

/** Displays a titled home content shelf using the selected layout preferences. */
@Composable
internal fun <T> HomeShelf(title: String, items: List<T>, key: (T) -> String, tile: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(items, key = key) { tile(it) }
        }
    }
}

/** Displays a focusable home content tile with artwork, metadata and an activation callback. */
@Composable
internal fun ContentTile(title: String, art: String?, subtitle: String, progress: Float? = null, logo: Boolean = false, size: HomeCardSize = HomeCardSize.MEDIUM, artworkBackground: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "homeTileFocus")
    val style = LocalHomeTileStyle.current
    val presentation = style.presentation
    val width = homeCardWidth(presentation.layout, size).dp
    val shape = RoundedCornerShape(presentation.corners.radius.dp)
    Column(
        Modifier.width(width).graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) style.onArtwork(if (artworkBackground) art else null)
            }.clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .4f), shape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(if (artworkBackground && presentation.layout == HomeLayout.CINEMA) width * 1.35f else width * (106f / 190f)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            if (art.isNullOrBlank()) Icon(Icons.Filled.LiveTv, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else AsyncImage(art, null, Modifier.fillMaxSize().padding(if (logo) 20.dp else 0.dp), contentScale = if (logo) ContentScale.Fit else ContentScale.Crop)
        }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (presentation.metadata) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
