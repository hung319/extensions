package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.DataStore
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest

// Lớp dữ liệu để truyền thông tin giữa `load` và `loadLinks`
data class LinkData(
    val url: String,
    val title: String,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null
)

// Xác định lớp provider chính
class BluPhimProvider : MainAPI() {
    override var mainUrl = "https://bluphim.uk.com"
    override var name = "BluPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val response = chain.proceed(request)
                if (response.code == 200 && response.body != null) {
                    val url = request.url.toString()
                    if (url.contains(".m3u8")) {
                        val body = response.body!!.string()
                        val newBody = body.replace("https://bluphim.uk.com", mainUrl)
                        return response.newBuilder().body(
                            okhttp3.ResponseBody.create(
                                response.body!!.contentType(),
                                newBody
                            )
                        ).build()
                    }
                }
                return response
            }
        }
    }

    override val mainPage = mainPageOf(
        "phim-hot" to "Phim Hot",
        "/the-loai/phim-moi-" to "Phim Mới",
        "/the-loai/phim-cap-nhat-" to "Phim Cập Nhật",
        "/the-loai/phim-bo-" to "Phim Bộ",
        "/the-loai/phim-le-" to "Phim Lẻ",
        "/the-loai/phim-chieu-rap-" to "Phim Chiếu Rạp"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data == "phim-hot") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = app.get(mainUrl).document
            val movies = document.select("div.list-films.film-hot ul#film_hot li.item").mapNotNull {
                it.toSearchResult()
            }
            return newHomePageResponse(request.name, movies, false)
        }

        val url = mainUrl + request.data + page
        val document = app.get(url).document
        val movies = document.select("div.list-films.film-new div.item").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, movies, movies.isNotEmpty())
    }

    private fun Element.toSearchResult(): MovieSearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("div.text span.title a")?.text()?.trim()
            ?: this.selectFirst("div.name")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        val fullPosterUrl = posterUrl?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, fullHref) {
            this.posterUrl = fullPosterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?k=$query"
        val document = app.get(url).document
        return document.select("div.list-films.film-new li.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.text h1 span.title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.poster img")?.let {
            val src = it.attr("src")
            if (src.startsWith("http")) src else "$mainUrl$src"
        }
        val year = document.select("div.dinfo dl.col dt:contains(Năm sản xuất) + dd")
            .text().toIntOrNull()
        val description = document.selectFirst("div.detail div.tab")?.text()?.trim()
        val ratingString = document.select("div.dinfo dl.col dt:contains(Điểm IMDb) + dd a")
            .text().trim()
        val score = runCatching { Score.from(ratingString, 10) }.getOrNull()
        val genres = document.select("dd.theloaidd a").map { it.text() }
        val recommendations = document.select("div.list-films.film-hot ul#film_related li.item").mapNotNull {
            it.toSearchResult()
        }
        val tvType = if (document.select("dd.theloaidd a:contains(TV Series - Phim bộ)").isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        val watchUrl = document.selectFirst("a.btn-see.btn-stream-link")?.attr("href")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        } ?: url

        return if (tvType == TvType.TvSeries) {
            val episodes = getEpisodes(watchUrl, title, year)
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
            }
        } else {
            val linkData = LinkData(url = watchUrl, title = title, year = year).toJson()
            newMovieLoadResponse(title, url, tvType, linkData) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun getEpisodes(watchUrl: String, title: String, year: Int?): List<Episode> {
        val document = app.get(watchUrl).document
        val episodeList = ArrayList<Episode>()
        document.select("div.list-episode a").forEach { element ->
            val href = element.attr("href")
            val name = element.text().trim()
            val epNum = name.filter { it.isDigit() }.toIntOrNull()
            if (href.isNotEmpty() && !name.contains("Server", ignoreCase = true)) {
                val linkData = LinkData(url = if (href.startsWith("http")) href else "$mainUrl$href", title = title, year = year, episode = epNum).toJson()
                episodeList.add(newEpisode(linkData) {
                    this.name = name
                    this.episode = epNum
                })
            }
        }
        return episodeList
    }

    private fun String.md5(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        
        val document = app.get(linkData.url).document
        
        // Sửa lỗi: Gọi fixUrl chỉ với một tham số
        val iframeStreamSrc = fixUrl(document.selectFirst("iframe#iframeStream")?.attr("src") ?: return false)
        val iframeStreamDoc = app.get(iframeStreamSrc, referer = linkData.url).document
        
        // Sửa lỗi: Gọi fixUrl chỉ với một tham số
        val iframeEmbedSrc = fixUrl(iframeStreamDoc.selectFirst("iframe#embedIframe")?.attr("src") ?: return false)

        val oPhimScript = app.get(iframeEmbedSrc, referer = iframeStreamSrc).document
            .select("script").find { it.data().contains("var url = '") }?.data()
        
        if (oPhimScript != null) {
            val url = oPhimScript.substringAfter("var url = '").substringBefore("'")
            val sourceName = if (url.contains("opstream")) "Ổ Phim" else "KKPhim"
            callback.invoke(ExtractorLink(
                source = this.name,
                name = sourceName,
                url = url,
                referer = iframeEmbedSrc,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            ))
            return true
        }

        val playerDoc = app.get(iframeEmbedSrc, referer = iframeStreamSrc).document
        val script = playerDoc.select("script").find { it.data().contains("var videoId =") }?.data() ?: return false
        
        val videoId = script.substringAfter("var videoId = '").substringBefore("'")
        val cdn = script.substringAfter("var cdn = '").substringBefore("'")
        val domain = script.substringAfter("var domain = '").substringBefore("'")

        val context = AcraApplication.context!!
        val prefs = context.getSharedPreferences("bluphim_prefs", Context.MODE_PRIVATE)
        var token = prefs.getString("bluphim_token", null)
        if (token == null) {
            token = "r" + System.currentTimeMillis()
            prefs.edit().putString("bluphim_token", token).apply()
        }
        
        val videoResponse = app.post(
            url = "${getBaseUrl(iframeEmbedSrc)}/geturl",
            data = mapOf(
                "videoId" to videoId,
                "id" to token.md5(),
                "domain" to domain
            ),
            referer = iframeEmbedSrc,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsed<VideoResponse>()

        if (videoResponse.status == "success") {
            val sourceUrl = "$cdn/${videoResponse.url}"
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "BluPhim",
                    url = sourceUrl,
                    referer = getBaseUrl(iframeEmbedSrc) + "/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }

        val tracks = script.substringAfter("tracks: [", "").substringBefore("]", "")
        if (tracks.isNotEmpty()) {
            val subs = Regex("""\{"file":"([^"]+)","label":"([^"]+)"}""").findAll(tracks)
            subs.forEach { sub ->
                val subUrl = sub.groupValues[1].replace("\\", "")
                val subLabel = sub.groupValues[2]
                subtitleCallback.invoke(
                    SubtitleFile(subLabel, subUrl)
                )
            }
        }
        return true
    }

    private fun getBaseUrl(url: String): String {
        return url.substringBefore("/video/")
    }

    data class VideoResponse(
        val status: String,
        val url: String
    )
}
