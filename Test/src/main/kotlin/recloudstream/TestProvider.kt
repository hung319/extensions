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
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// Sửa đổi: LinkData chứa cả 2 server
data class LinkData(
    val server1Url: String,
    val server2Url: String?, // Có thể null nếu chỉ có 1 server
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
        TvType.TvSeries,
        TvType.Anime
    )

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        // Interceptor giữ nguyên để sửa lỗi domain trong file m3u8 nếu có
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

        val isSeries = document.select("dd.theloaidd a:contains(TV Series - Phim bộ)").isNotEmpty()
        val tvType = if (genres.any { it.equals("Hoạt hình", ignoreCase = true) }) {
            TvType.Anime
        } else if (isSeries) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        
        val watchUrl = document.selectFirst("a.btn-see.btn-stream-link")?.attr("href")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        } ?: url

        return if (isSeries) {
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
            val server2Url = app.get(watchUrl).document.selectFirst("a[href*=?sv2=true]")?.attr("href")?.let {
                if (it.startsWith("http")) it else mainUrl + it
            }
            val linkData = LinkData(server1Url = watchUrl, server2Url = server2Url, title = title, year = year).toJson()
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
        document.select("div.list-episode a:not([href*=?sv2=true])").forEach { element ->
            val href = element.attr("href")
            val name = element.text().trim()
            val epNum = name.filter { it.isDigit() }.toIntOrNull()

            if (href.isNotEmpty() && !name.contains("Server", ignoreCase = true)) {
                val server1Url = if (href.startsWith("http")) href else "$mainUrl$href"
                val server2Url = element.nextElementSibling()?.let { next ->
                    if (next.attr("href").contains("?sv2=true")) {
                        if (next.attr("href").startsWith("http")) next.attr("href") else mainUrl + next.attr("href")
                    } else null
                } ?: "$server1Url?sv2=true" 

                val linkData = LinkData(server1Url, server2Url, title, null, epNum).toJson()
                episodeList.add(newEpisode(linkData) {
                    this.name = name
                    this.episode = epNum
                })
            }
        }
        return episodeList
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        try {
            return URI(baseUrl).resolve(url).toString()
        } catch (e: Exception) {
            return "$mainUrl$url"
        }
    }

    // =========================================================================
    // THAY ĐỔI LỚN: Viết lại hoàn toàn hàm loadLinks để phù hợp cấu trúc mới
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val linkData = parseJson<LinkData>(data)
        val host = URI(mainUrl).host

        // Chạy song song cả hai server
        async {
            try {
                // SERVER GỐC - LOGIC MỚI
                val document = app.get(linkData.server1Url).document
                val iframeStreamSrc = fixUrl(document.selectFirst("iframe#iframeStream")?.attr("src") ?: return@async, linkData.server1Url)

                // Chỉ thực thi nếu là iframe streaming mới
                if (iframeStreamSrc.contains("/streaming")) {
                    // Lấy các token và id trực tiếp từ URL của iframe
                    val cdn = iframeStreamSrc.substringAfter("cdn=").substringBefore("&")
                    val videoId = iframeStreamSrc.substringAfter("id=").substringBefore("&")
                    val token1 = iframeStreamSrc.substringAfter("token1=").substringBefore("&")
                    val token2 = iframeStreamSrc.substringAfter("token2=").substringBefore("&")
                    val token3 = iframeStreamSrc.substringAfter("token3=").substringBefore("&")
                    
                    // 1. Tải link video
                    val finalUrl = "$cdn/segment/$videoId/?token1=$token1&token3=$token3"
                    callback.invoke(ExtractorLink(name, "Server Gốc", finalUrl, referer = "$cdn/", quality = Qualities.P1080.value, type = ExtractorLinkType.M3U8))

                    // 2. Tải link phụ đề
                    val iframeStreamDoc = app.get(iframeStreamSrc, referer = linkData.server1Url).document
                    // Tìm script chứa thông tin phụ đề
                    val script = iframeStreamDoc.selectFirst("script:containsData(subtitles)")?.data()

                    if (script != null) {
                        // Trích xuất chuỗi array phụ đề
                        val tracks = script.substringAfter("subtitles: [", "").substringBefore("]", "")
                        // Regex mới để lấy id và label của phụ đề
                        val subRegex = Regex("""\{\s*id:\s*'([^']*)',\s*label:\s*'([^']*)'[^}]*\}""")

                        subRegex.findAll(tracks).forEach { match ->
                            val subId = match.groupValues[1]
                            val subLabel = match.groupValues[2]
                            // Dựng lại URL phụ đề theo cấu trúc mới
                            val subUrl = "$cdn/subtitle/$subId?web=$host&token2=$token2"
                            subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        async {
            // SERVER BÊN THỨ 3 (giữ nguyên, không thay đổi)
            if (linkData.server2Url != null) {
                try {
                    val document = app.get(linkData.server2Url).document
                    val iframeStreamSrc = fixUrl(document.selectFirst("iframe#iframeStream")?.attr("src") ?: return@async, linkData.server2Url)
                    val iframeStreamDoc = app.get(iframeStreamSrc, referer = linkData.server2Url).document
                    val iframeEmbedSrc = fixUrl(iframeStreamDoc.selectFirst("iframe#embedIframe")?.attr("src") ?: return@async, iframeStreamSrc)
                    val oPhimScript = app.get(iframeEmbedSrc, referer = iframeStreamSrc).document.select("script").find { it.data().contains("var url = '") }?.data()
                    
                    if (oPhimScript != null) {
                        val url = oPhimScript.substringAfter("var url = '").substringBefore("'")
                        val sourceName = if (url.contains("opstream")) "Ổ Phim" else "KKPhim"
                        callback.invoke(ExtractorLink(name, "Server bên thứ 3 - $sourceName", url, iframeEmbedSrc, Qualities.Unknown.value, type = ExtractorLinkType.M3U8))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return@coroutineScope true
    }
}
