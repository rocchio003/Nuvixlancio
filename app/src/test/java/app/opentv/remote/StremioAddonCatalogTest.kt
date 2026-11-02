/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.remote

import app.opentv.data.remote.StremioClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the add-on client's catalog and meta paths — manifest shelf parsing, the
 * standard extra paging, episode id building and the direct-URL-only stream filter that keeps
 * torrent replies out of the picker.
 */
class StremioAddonCatalogTest {
    private lateinit var server: MockWebServer
    private lateinit var client: StremioClient
    private lateinit var manifestUrl: String

    /** Creates isolated fixtures before each test. */
    @Before fun start() {
        server = MockWebServer(); server.start()
        client = StremioClient(OkHttpClient())
        manifestUrl = server.url("/manifest.json").toString()
    }

    /** Releases test resources and restores shared state after each test. */
    @After fun stop() { server.shutdown() }

    /** Queues a synthetic JSON response on the local mock add-on. */
    private fun respond(body: String) { server.enqueue(MockResponse().setBody(body)) }

    /** Checks: a manifest with catalog shelves stores only movie and series shelves with genre options. */
    @Test fun `manifest catalog shelves keep movie and series entries with genre options`() {
        respond(
            "{\"id\":\"org.test.addon\",\"name\":\"Test Addon\",\"types\":[\"movie\",\"series\"]," +
                "\"resources\":[\"stream\",\"catalog\"],\"catalogs\":[" +
                "{\"id\":\"t10.movie.top10\",\"name\":\"Top 10\",\"type\":\"movie\",\"extra\":[{\"name\":\"skip\"}]}," +
                "{\"id\":\"tmdb.series.popular\",\"name\":\"Popular Series\",\"type\":\"series\",\"extra\":" +
                "[{\"name\":\"genre\",\"options\":[\"Drama\",\"2024\",\"\"]},{\"name\":\"skip\",\"isRequired\":false}]}," +
                "{\"id\":\"other.anime.list\",\"name\":\"Anime\",\"type\":\"anime\"}]}",
        )
        val addon = client.fetchAddon(manifestUrl)!!
        assertThat(addon.name).isEqualTo("Test Addon")
        assertThat(addon.catalogs).hasSize(2)
        assertThat(addon.catalogs[0].name).isEqualTo("Top 10")
        assertThat(addon.catalogs[0].genres).isEmpty()
        assertThat(addon.catalogs[1].genres).containsExactly("Drama", "2024")
    }

    /** Checks: a non-manifest payload is rejected. */
    @Test fun `non-manifest payloads are rejected`() {
        respond("{\"error\":\"nope\"}")
        assertThat(client.fetchAddon(manifestUrl)).isNull()
    }

    /** Checks: catalog pagination requests the queried shelf with skip and genre extras. */
    @Test fun `catalog pages with skip and genre extras`() = runBlocking {
        respond(
            "{\"metas\":[" +
                "{\"id\":\"tt1111111\",\"type\":\"movie\",\"name\":\"Alpha\",\"poster\":\"p1.jpg\"," +
                "\"releaseInfo\":\"2024\",\"background\":\"bg1.jpg\",\"genres\":[\"Drama\"]}," +
                "{\"id\":\"tt2222222\",\"type\":\"movie\",\"name\":\"Beta\"}]}",
        )
        val metas = client.catalog(manifestUrl, "movie", "t10.movie.top10", skip = 100, genre = "Drama")
        val request = server.takeRequest()
        assertThat(request.path)
            .isEqualTo("/catalog/movie/t10.movie.top10.json?extras=%7B%22skip%22%3A%22100%22%2C%22genre%22%3A%22Drama%22%7D")
        assertThat(metas).hasSize(2)
        assertThat(metas[0].name).isEqualTo("Alpha")
        assertThat(metas[0].posterUrl).isEqualTo("p1.jpg")
        assertThat(metas[0].backdropUrl).isEqualTo("bg1.jpg")
        assertThat(metas[0].releaseInfo).isEqualTo("2024")
        assertThat(metas[1].posterUrl).isNull()
    }

    /** Checks: series meta episodes build the baseId-anchored stream ids, explicit ids win. */
    @Test fun `series episodes build stream ids`() = runBlocking {
        respond(
            "{\"meta\":{\"id\":\"tt3333333\",\"type\":\"series\",\"name\":\"Gamma\",\"description\":\"Drama\"," +
                "\"cast\":[\"Ann\",\"Bob\"],\"genres\":[\"Drama\"],\"videos\":[" +
                "{\"season\":1,\"episode\":2,\"title\":\"Second\"}," +
                "{\"id\":\"tt3333333:2:5\",\"season\":2,\"episode\":5,\"name\":\"Fifth\"}]}}",
        )
        val meta = client.meta(manifestUrl, "series", "tt3333333")!!
        assertThat(meta.description).isEqualTo("Drama")
        assertThat(meta.cast).containsExactly("Ann", "Bob")
        assertThat(meta.videos).hasSize(2)
        assertThat(meta.videos[0].streamId).isEqualTo("tt3333333:1:2")
        assertThat(meta.videos[0].title).isEqualTo("Second")
        // The add-on's own episode id is the stream id when it already carries season:episode.
        assertThat(meta.videos[1].streamId).isEqualTo("tt3333333:2:5")
        assertThat(meta.videos[1].title).isEqualTo("Fifth")
    }

    /** Checks: a meta that answers empty produces null rather than a blank title. */
    @Test fun `empty meta answer produces null`() = runBlocking {
        respond("{\"meta\":{}}")
        assertThat(client.meta(manifestUrl, "series", "tt0000000")).isNull()
    }

    /** Checks: stream replies keep direct URLs and drop infoHash-only torrent entries. */
    @Test fun `streams keep url entries and drop torrents`() = runBlocking {
        respond(
            "{\"streams\":[" +
                "{\"name\":\"4K\",\"description\":\"big file\",\"url\":\"https://cdn/stream.m3u8\"}," +
                "{\"name\":\"Torrent\",\"infoHash\":\"abc\",\"fileIdx\":0}]}",
        )
        val streams = client.streams(manifestUrl, "Easy Streams", "movie", "tt0468569")
        assertThat(streams).hasSize(1)
        assertThat(streams[0].url).isEqualTo("https://cdn/stream.m3u8")
        // Description, not the short name, is the human-readable line when both exist.
        assertThat(streams[0].title).isEqualTo("big file")
        assertThat(streams[0].addonName).isEqualTo("Easy Streams")
    }
}
