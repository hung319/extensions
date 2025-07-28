package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.Response

// Xác định lớp provider chính
class BluPhimProvider : MainAPI() {
    // Ghi đè các thuộc tính cơ bản của API
    override var mainUrl = "https://bluphim.uk.com"
    override var name = "BluPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Thêm interceptor để xử lý link video và phụ đề
    override val interceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            // Logic làm sạch M3U8 từ các nguồn khác nhau
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


    // mainPage định nghĩa tất cả các mục trên trang chủ
    override val mainPage = mainPageOf(
        "phim-hot" to "Phim Hot",
        "/the-loai/phim-moi-" to "Phim Mới",
        "/the-loai/phim-cap-nhat-" to "Phim Cập Nhật",
        "/the-loai/phim-bo-" to "Phim Bộ",
        "/the-loai/phim-le-" to "Phim Lẻ",
        "/the-loai/phim-chieu-rap-" to "Phim Chiếu Rạp"
    )

    // getMainPage xử lý việc tải các trang cho từng mục
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Xử lý mục "Phim Hot" (không phân trang)
        if (request.data == "phim-hot") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

            val document = app.get(mainUrl).document
            val movies = document.select("div.list-films.film-hot ul#film_hot li.item").mapNotNull {
                it.toSearchResult()
            }
            return newHomePageResponse(request.name, movies, false)
        }

        // Xử lý các mục có phân trang
        val url = mainUrl + request.data + page
        val document = app.get(url).document

        val movies = document.select("div.list-films.film-new div.item").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, movies, movies.isNotEmpty())
    }

    // Hàm tiện ích được tối ưu để xử lý tất cả các layout
    private fun Element.toSearchResult(): MovieSearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null

        val title = this.selectFirst("div.text span.title a")?.text()?.trim() // Dành riêng cho layout "Phim Hot"
            ?: this.selectFirst("div.name")?.text()?.trim() // Dành cho TẤT CẢ các layout còn lại
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() } // Dự phòng
            ?: return null

        val posterUrl = this.selectFirst("img")?.attr("src")
        
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        val fullPosterUrl = posterUrl?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return newMovieSearchResponse(title, fullHref) {
            this.posterUrl = fullPosterUrl
        }
    }

    // Hàm để xử lý các truy vấn tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?k=$query"
        val document = app.get(url).document

        return document.select("div.list-films.film-new li.item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm để tải thông tin chi tiết cho một bộ phim
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

        return if (tvType == TvType.TvSeries) {
            val watchUrl = document.selectFirst("a.btn-see.btn-stream-link")?.attr("href")
            val episodes = if (watchUrl != null) getEpisodes(watchUrl) else emptyList()

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
            }
        }
    }

    // Hàm để lấy danh sách các tập
    private suspend fun getEpisodes(watchUrl: String): List<Episode> {
        val fullWatchUrl = if (watchUrl.startsWith("http")) watchUrl else "$mainUrl$watchUrl"
        val document = app.get(fullWatchUrl).document
        val episodeList = ArrayList<Episode>()

        document.select("div.list-episode a").forEach { element ->
            val href = element.attr("href")
            val name = element.text().trim()
            if (href.isNotEmpty() && !name.contains("Server", ignoreCase = true)) {
                episodeList.add(newEpisode(if (href.startsWith("http")) href else "$mainUrl$href") {
                    this.name = name
                })
            }
        }
        return episodeList
    }

    // Hàm để tải các liên kết
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframeStreamSrc = document.selectFirst("iframe#iframeStream")?.attr("src") ?: return false
        val iframeStreamDoc = app.get(iframeStreamSrc).document
        val iframeEmbedSrc = iframeStreamDoc.selectFirst("iframe#embedIframe")?.attr("src") ?: return false
        
        val playerDoc = app.get(iframeEmbedSrc, referer = iframeStreamSrc).document
        val script = playerDoc.select("script").find { it.data().contains("var videoId =") }?.data() ?: return false
        
        val videoId = script.substringAfter("var videoId = '").substringBefore("'")
        val cdn = script.substringAfter("var cdn = '").substringBefore("'")
        val domain = script.substringAfter("var domain = '").substringBefore("'")

        var token = DataStore.getKey<String>("bluphim_token")
        if (token == null) {
            token = "r" + System.currentTimeMillis()
            DataStore.setKey("bluphim_token", token)
        }
        
        val linkData = app.post(
            url = "${getBaseUrl(iframeEmbedSrc)}/geturl",
            data = mapOf(
                "videoId" to videoId,
                "id" to md5(token),
                "domain" to domain
            ),
            referer = iframeEmbedSrc,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsed<VideoResponse>()

        if (linkData.status == "success") {
            val sourceUrl = "$cdn/${linkData.url}"
            callback.invoke(
                ExtractorLink(
                    source = this.name, // Sửa: đổi 'name' thành 'source'
                    name = this.name,
                    url = sourceUrl,
                    referer = getBaseUrl(iframeEmbedSrc) + "/",
                    quality = Qualities.P1080.value,
                    // SỬA LỖI: Sử dụng ExtractorLinkType.M3U8 thay vì isM3u8
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

    // Lớp dữ liệu để phân tích JSON trả về
    data class VideoResponse(
        val status: String,
        val url: String
    )
}
