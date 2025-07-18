package recloudstream 

// Thêm các thư viện cần thiết
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import org.jsoup.nodes.Element

/**
 * Định nghĩa lớp Provider chính
 */
class Anime47Provider : MainAPI() {
    // === Các thuộc tính cơ bản của Provider ===
    override var mainUrl = "https://anime47.shop"
    override var name = "Anime47"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Cloudflare Killer để vượt qua bảo vệ Cloudflare
    private val interceptor = CloudflareKiller()

    // === Định nghĩa các trang chính hiển thị trên trang chủ của plugin ===
    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi/1.html" to "Anime Mới Cập Nhật",
        "$mainUrl/the-loai/hoat-hinh-trung-quoc-75/1.html" to "Hoạt Hình Trung Quốc",
        "$mainUrl/danh-sach/anime-mua-moi-update.html" to "Anime Mùa Mới",
    )

    /**
     * Hàm để tải dữ liệu cho trang chủ.
     * @param page Số trang hiện tại.
     * @param request Yêu cầu trang chính chứa URL và tên.
     * @return Dữ liệu trang chủ.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("/1.html", "/$page.html")
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("ul.last-film-box > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    /**
     * Hàm tiện ích để chuyển đổi một phần tử HTML thành đối tượng SearchResponse.
     * @return SearchResponse hoặc null nếu không thể phân tích.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.movie-title-1")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = this.selectFirst("div.public-film-item-thumb")?.attr("style")
            ?.substringAfter("url('")?.substringBefore("')")
        val ribbon = this.selectFirst("span.ribbon")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (ribbon != null) {
                this.otherName = "Tập $ribbon"
            }
        }
    }

    /**
     * Hàm xử lý tìm kiếm.
     * @param query Từ khóa tìm kiếm.
     * @return Danh sách các kết quả tìm kiếm.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/tim-kiem/?keyword=$query", interceptor = interceptor).document
        return document.select("ul.last-film-box > li").mapNotNull {
            it.toSearchResult()
        }
    }

    /**
     * Hàm tải thông tin chi tiết của phim và danh sách các tập.
     * @param url URL của phim.
     * @return Dữ liệu chi tiết của phim.
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.movie-title span.title-1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.movie-l-img img")?.attr("src")
        val plot = document.selectFirst("div#film-content > .news-article")?.text()?.trim()
        val tags = document.select("dd.movie-dd.dd-cat a").map { it.text() }
        val year = document.selectFirst("span.title-year")?.text()?.removeSurrounding("(", ")")?.toIntOrNull()

        // Trích xuất danh sách tập phim từ biến javascript 'anyEpisodes'
        val script = document.select("script").find { it.data().contains("anyEpisodes =") }?.data()
        val episodesHtml = script?.substringAfter("anyEpisodes = '")?.substringBefore("';")
        
        if (episodesHtml == null) {
             return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
        
        // Dùng Jsoup để phân tích chuỗi HTML vừa trích xuất
        val episodesDoc = org.jsoup.Jsoup.parse(episodesHtml)
        val episodes = episodesDoc.select("div.episodes ul li a").map {
            val epHref = fixUrl(it.attr("href"))
            val epNum = it.attr("title").ifEmpty { it.text() }.toIntOrNull()
            val epName = "Tập " + it.attr("title").ifEmpty { it.text() }
            Episode(epHref, name = epName, episode = epNum)
        }.reversed()


        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    /**
     * Hàm tải link video trực tiếp để phát.
     * @param data URL của trang xem phim.
     * @param subtitleCallback Callback để trả về file phụ đề.
     * @param callback Callback để trả về link video.
     * @return true nếu tải link thành công.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        
        // Lấy ID tập phim từ script
        val scriptContent = document.select("script").find { it.data().contains("var id_ep =") }?.data()
        val episodeId = scriptContent?.substringAfter("var id_ep = ")?.substringBefore(";")?.trim() ?: return false

        // Lặp qua các server có sẵn
        document.select("#clicksv > span.btn").apmap { serverElement ->
            try {
                val serverId = serverElement.id().removePrefix("sv")
                val serverName = serverElement.attr("title")

                val playerResponseText = app.post(
                    url = "$mainUrl/player/player.php",
                    data = mapOf("ID" to episodeId, "SV" to serverId),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    interceptor = interceptor
                ).text

                // === GIẢI MÃ DỮ LIỆU VIDEO ===
                val encryptedData = playerResponseText.substringAfter("var thanhhoa = atob(\"").substringBefore("\");")
                val decryptionKey = "caphedaklak"

                val decryptedJson = AesHelper.cryptoJsDecrypt(encryptedData, decryptionKey)
                // Đôi khi link có thể nằm trong biến `daklak` hoặc `file:`
                val videoUrl = decryptedJson?.let {
                    if (it.startsWith("http")) it else it.substringAfter("file:\"").substringBefore("\"")
                }
                
                // Trích xuất phụ đề từ trong script
                val tracksRegex = """"tracks"\s*:\s*(\[.*?\])""".toRegex()
                val tracksMatch = tracksRegex.find(playerResponseText)
                if (tracksMatch != null) {
                    val tracksArrayJson = tracksMatch.groupValues[1]
                    val tracks = AppUtils.parseJson<List<Track>>(tracksArrayJson)
                    tracks.forEach { track ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = track.label ?: "Unknown",
                                url = fixUrl(track.file)
                            )
                        )
                    }
                }
                // ========================

                if (videoUrl != null && videoUrl.contains(".m3u8")) {
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "$name - $serverName",
                            url = videoUrl,
                            referer = "$mainUrl/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                        )
                    )
                }
            } catch (e: Exception) {
                // In ra lỗi để debug nhưng không làm dừng cả quá trình
                e.printStackTrace()
            }
        }
        
        return true
    }

    // Data class để parse JSON của danh sách phụ đề
    data class Track(
        val file: String,
        val label: String?,
        val kind: String?
    )
}
