/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.data.model.StremioAddon
import app.opentv.data.model.StremioCatalog
import app.opentv.data.model.StremioMeta
import app.opentv.data.model.StremioStream
import app.opentv.data.model.StremioVideo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A thin, read-only client for the open Stremio add-on protocol — the same protocol Stremio and
 * many Kodi add-ons speak. OpenTV ships with no add-ons and hosts no content; this only talks to
 * manifest URLs the user has pasted in themselves.
 *
 * ## What it does
 * - [fetchAddon] fetches an add-on's `manifest.json`, validates the basics (id + name) and captures
 *   the catalog shelves it declares, so set-up stores a richer view than just a name and the
 *   Movies/Shows tabs can offer the add-on's catalogs for browsing.
 * - [streams] asks an add-on for the streams it offers for a title and returns only the ones with a
 *   direct playable URL. Debrid- or provider-backed add-ons resolve a title to such a URL
 *   internally (building on the key baked into the user's personalised manifest URL), so OpenTV
 *   never handles a debrid key and never touches a torrent — it just plays the URL the add-on
 *   hands back. Raw torrent (infoHash-only) entries are skipped on purpose.
 * - [catalog] pages one of the add-on's catalogs (`/catalog/{type}/{id}.json` with the standard
 *   `skip`/`genre` extras). Nothing is persisted — the add-on's own catalog stays authoritative.
 * - [meta] fetches one title's full meta (`/meta/{type}/{id}.json`), used by the add-on detail
 *   page — chiefly to collect a series' episode list.
 *
 * ## Blocking on purpose
 * Methods block on the calling thread; callers already run them inside `withContext(Dispatchers.IO)`,
 * matching [TmdbClient].
 */
class StremioClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * Fetch and validate an add-on manifest: id + name required. `movie`/`series` catalog
     * resources become [StremioAddon.catalogs] shelves, with the manifest's genre extra options
     * when it declared them. Returns null when the URL doesn't resolve to a Stremio manifest.
     */
    fun fetchAddon(manifestUrl: String): StremioAddon? {
        val o = get(manifestUrl.trim().toHttpUrlOrNull() ?: return null)?.jsonObject ?: return null
        val name = ofString(o, "name") ?: return null
        if (o["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return null
        val catalogs = array(o, "catalogs")
            ?.mapNotNull { parseCatalog(it as? JsonObject) }.orEmpty()
        return StremioAddon(manifestUrl = manifestUrl.trim(), name = name, catalogs = catalogs)
    }

    /** Kept for callers that only need the display name (the validation flow). */
    fun fetchManifest(manifestUrl: String): String? = fetchAddon(manifestUrl)?.name

    /**
     * One page of [catalogId] — a manifest-declared shelf of [addonManifestUrl]. [skip] pages the
     * add-on throughout its library in its own page size; [genre], when the manifest offered
     * selector options, is passed through as the `genre` extra. Returns the title list exactly as
     * the add-on answered.
     */
    fun catalog(
        addonManifestUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        genre: String?,
    ): List<StremioMeta> {
        val payload = buildMap {
            put("skip", skip.toString())
            genre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
        }
        val url = buildUrl(addonManifestUrl, "catalog", type, catalogId) ?: return emptyList()
        val response = get(url.newBuilder().addQueryParameter("extras", json.encodeToString(payload)).build())
        val metas = array(response?.jsonObject ?: return emptyList(), "metas")
        return metas?.mapNotNull { parseMeta(it as? JsonObject, type) }.orEmpty()
    }

    /**
     * The full meta for a title — the same entry shape the catalog responses carry, resolved one
     * depth deeper. Returns null when the add-on has nothing for it (add-ons answer `{"meta":{}}`
     * for ids they don't carry).
     */
    fun meta(addonManifestUrl: String, type: String, id: String): StremioMeta? {
        val url = buildUrl(addonManifestUrl, "meta", type, id) ?: return null
        val response = get(url) ?: return null
        val m = response.jsonObject["meta"] as? JsonObject ?: return null
        val meta = parseMeta(m, type) ?: return null
        val videos = array(m, "videos")
            ?.mapNotNull { parseVideo(it as? JsonObject, meta.id) }.orEmpty()
        return meta.copy(videos = videos)
    }

    /**
     * The streams [addonManifestUrl] offers for a title. [id] must be the id the add-ons themselves
     * use — `tt1234567` for a film, `tt1234567:1:3` or `tmdb:series:…:1:3` for series. Only entries
     * with a direct `url` are returned (see the class note on why torrents are skipped).
     */
    fun streams(addonManifestUrl: String, addonName: String, type: String, id: String): List<StremioStream> {
        val url = buildUrl(addonManifestUrl, "stream", type, id) ?: return emptyList()
        val streams = array(get(url)?.jsonObject ?: return emptyList(), "streams")
        return streams?.mapNotNull { element ->
            val s = (element as? JsonObject) ?: return@mapNotNull null
            val streamUrl = ofString(s, "url")
                ?: return@mapNotNull null // infoHash-only (raw torrent) — skipped on purpose
            val label = (ofString(s, "title") ?: ofString(s, "description") ?: ofString(s, "name"))
                ?.replace('\n', ' ')?.trim() ?: "Stream"
            StremioStream(url = streamUrl, title = label.ifBlank { "Stream" }, addonName = addonName)
        }.orEmpty()
    }

    /** Builds `<base>/{resource}/{type}/{id}.json` — resource URLs hang off the manifest's own base. */
    private fun buildUrl(manifestUrl: String, resource: String, type: String, id: String): HttpUrl? {
        val base = manifestUrl.trim().removeSuffix("manifest.json").trimEnd('/')
        if (base.isBlank()) return null
        return "$base/$resource/$type/$id.json".toHttpUrlOrNull()
    }

    /** Reads a plain string field, blank mapped to null. */
    private fun ofString(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Reads a JSON array of plain strings, dropping blanks. */
    private fun ofStringList(o: JsonObject, key: String): List<String>? =
        array(o, key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

    /** Reads a JSON array field, or null when the field is absent or not an array. */
    private fun array(o: JsonObject, key: String): List<JsonElement>? =
        o[key] as? JsonArray

    /** One manifest `catalogs` entry → a browsable shelf; only movie/series shelves are kept. */
    private fun parseCatalog(c: JsonObject?): StremioCatalog? {
        if (c == null) return null
        val id = ofString(c, "id") ?: return null
        val type = ofString(c, "type") ?: return null
        if (type !in setOf("movie", "series")) return null
        val genres = array(c, "extra")
            ?.filterIsInstance<JsonObject>()
            ?.filter { ofString(it, "name") == "genre" }
            ?.flatMap { ofStringList(it, "options").orEmpty() }
            ?.distinct()
            .orEmpty()
        return StremioCatalog(id = id, name = ofString(c, "name") ?: id, type = type, genres = genres)
    }

    /** One catalog- or meta-response entry → a title; add-ons differ in which fields they fill. */
    private fun parseMeta(m: JsonObject?, type: String): StremioMeta? {
        if (m == null) return null
        val id = ofString(m, "id") ?: return null
        val name = ofString(m, "name") ?: return null
        return StremioMeta(
            id = id,
            type = ofString(m, "type") ?: type,
            name = name,
            posterUrl = ofString(m, "poster"),
            backdropUrl = ofString(m, "background") ?: ofString(m, "banner"),
            releaseInfo = ofString(m, "releaseInfo") ?: ofString(m, "year"),
            description = ofString(m, "description"),
            cast = ofStringList(m, "cast").orEmpty(),
            genres = ofStringList(m, "genres").orEmpty(),
        )
    }

    /** One meta `videos` entry → an episode, carrying the id the stream call is made with. */
    private fun parseVideo(v: JsonObject?, baseId: String): StremioVideo? {
        if (v == null) return null
        val season = v["season"]?.jsonPrimitive?.intOrNull ?: return null
        val episode = (v["episode"] ?: v["number"])?.jsonPrimitive?.intOrNull ?: return null
        val title = ofString(v, "title") ?: ofString(v, "name")
        // Stremio's convention here is `baseId:season:episode`; some add-ons already list that
        // exact id under `id`/`streamId` — either way the stream call gets the right ids.
        val streamId = (ofString(v, "id") ?: ofString(v, "streamId"))
            ?.takeIf { it.endsWith(":$season:$episode") }
            ?: "$baseId:$season:$episode"
        return StremioVideo(season = season, episode = episode, title = title, streamId = streamId)
    }

    /** Fetches and parses JSON from an add-on endpoint. */
    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url).header("User-Agent", "Nuvix").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body)
            }
        }.onFailure { Log.w(TAG, "Stremio request failed") }.getOrNull()
    }

    private companion object {
        const val TAG = "StremioClient"
    }
}
