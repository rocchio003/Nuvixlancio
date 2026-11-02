/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

import kotlinx.serialization.Serializable

/**
 * A user-added Stremio add-on: a small web service (identified by its manifest URL) that, given a
 * title, returns playable stream links. OpenTV ships with none — the user pastes the manifest URL
 * of an add-on they have configured themselves (including any debrid key, which is baked into that
 * personalised URL on the add-on's own site, never entered into OpenTV). Stored on-device only,
 * exactly like a provider credential.
 *
 * This is the same open protocol Stremio and Kodi speak; OpenTV is only a neutral client for it and
 * hosts, indexes and bundles no content of its own.
 */
@Serializable
data class StremioAddon(
    /** The manifest URL the user pasted, e.g. https://…/manifest.json. Its identity for de-dup. */
    val manifestUrl: String,
    /** Display name read from the add-on's manifest at add time. */
    val name: String,
    /**
     * The catalog shelves the add-on declared in its manifest (`catalogs` resources), captured at
     * add time. Each one becomes a browsable catalog inside the Movies/Shows tabs. Empty for
     * stream-only add-ons — as with Easy Streams — and read-only: the shelves are the add-on's,
     * their contents are fetched live while browsing and nothing is stored locally.
     */
    val catalogs: List<StremioCatalog> = emptyList(),
)

/**
 * One browsable catalog shelf an add-on exposes, e.g. "Netflix Top 10 United States"
 * (`t10.movie.netflix_top10`) or "Popular" (`tmdb.movie.popular`). [type] is the Stremio type the
 * catalog serves — movie or series — and [genres] holds the extra-selector options the manifest
 * declared it with (genre names, years, "Day"/"Week" trending windows), shown as sub-filters when present.
 */
@Serializable
data class StremioCatalog(
    /** The catalog id from the manifest, e.g. "tmdb.movie.popular". */
    val id: String,
    /** The catalog display name from the manifest. */
    val name: String,
    /** The Stremio type this catalog serves: "movie" or "series". */
    val type: String,
    /** Optional extra-selector options from the manifest (genre names / years / trending windows). */
    val genres: List<String> = emptyList(),
)

/**
 * A title an add-on catalog returned. [id] carries the add-on's own prefixing convention — `tt…`
 * (IMDb), `tmdb:movie:…` / `tmdb:series:…`, `kitsu:…` — and it is what stream requests for this
 * title must be addressed with, in place of the IMDb id provider titles resolve through TMDB.
 */
data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val posterUrl: String? = null,
    /** A wide background image the add-on supplies for detail pages, when it does. */
    val backdropUrl: String? = null,
    val releaseInfo: String? = null,
    val description: String? = null,
    val cast: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    /** Episodes, for series metas; empty for movies. */
    val videos: List<StremioVideo> = emptyList(),
)

/** One episode of an add-on series meta: what `/meta/series/{id}.json` lists under "videos". */
data class StremioVideo(
    val season: Int?,
    val episode: Int?,
    val title: String?,
    /** The stream-list id for this episode (`baseId:season:episode`), or null when unknowable. */
    val streamId: String?,
)

/**
 * A single playable stream an add-on returned for a title. OpenTV keeps only streams that carry a
 * direct, ready-to-play [url] (what debrid-backed add-ons return); raw torrent (infoHash-only)
 * entries are dropped, because OpenTV plays URLs and deliberately does no torrenting itself.
 */
data class StremioStream(
    val url: String,
    /** A human label for the quality/source line the add-on supplied (e.g. "4K • 12GB"). */
    val title: String,
    /** Which add-on produced this, for grouping in the picker. */
    val addonName: String,
)
