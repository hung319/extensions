package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

// import com.lagradost.cloudstream3.utils.ExtractorApiKt.newExtractorLink

// Kế thừa từ MainAPI (chuẩn TV/Movie)
class Vn2Provider : MainAPI() { 
    override var mainUrl = "https://www.vn2b.my"
    override var name = "Phim Vn2"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // ================================
    //         TRANG CHỦ
    // ================================

    override val mainPage = mainPageOf(
        "phim-moi-vn2-phim-2vn-phim/new.aspx" to "Phim Mới Cập Nhật",
        "danh-muc/7/trung-quoc.aspx" to "Phim Trung Quốc",
        "danh-muc/10/han-quoc.aspx" to "Phim Hàn Quốc",
        "danh-muc/8/thai-lan.aspx" to "Phim Thái Lan",
        "the-loai/29/hoat-hinh-anime.aspx" to "Phim Hoạt Hình"
    )

    // Dùng getMainPage (chuẩn API mới)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = request.data
        val url = if (page == 1) "$mainUrl/$data" else "$mainUrl/$data?page=$page"
        
        val document = app.get(url).document
        
        val items = document.select("div.Form2").mapNotNull { element ->
            val a = element.selectFirst("p.Form2Text a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = a.text()
            val img = element.selectFirst("img.c10")?.attr("src")
            // val episode = element.selectFirst("div.thongtintapphim span.boxtt_tap")?.text()?.trim()

            // SỬA: Dùng newMovieSearchResponse (chuẩn MainAPI)
            // Cấu trúc MovieSearchResponse bạn cung cấp không có addDubStatus.
            newMovieSearchResponse(title, href, TvType.TvSeries) { 
                posterUrl = fixUrl(img)
                // ĐÃ LOẠI BỎ addDubStatus GÂY LỖI
            }
        }
        
        return newHomePageResponse(request.name, items)
    }

    // ================================
    //           TÌM KIẾM
    // ================================

    override suspend fun search(query: String): List<SearchResponse> {
        // SỬA: Thay thế khoảng trắng bằng dấu '+'
        // Các URL thường không xử lý tốt khoảng trắng.
        val formattedQuery = query.replace(" ", "+")
        val url = "$mainUrl/tim-kiem/$formattedQuery.aspx"
        
        val document = app.get(url).document

        // Selectors (div.boxtk, p.nametk a, img.c10)
        // đã được xác nhận là đúng dựa trên file HTML 'www.vn2b.my_tim-kiem_vuon.aspx.html'
        return document.select("div.boxtk").mapNotNull {
            val a = it.selectFirst("p.nametk a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = a.text()
            // img.c10 nằm bên trong div.boxtk_img, nhưng .selectFirst("img.c10") vẫn tìm được
            val img = it.selectFirst("img.c10")?.attr("src")

            // Dùng newMovieSearchResponse (chuẩn MainAPI)
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrl(img)
                // Đã loại bỏ 'addDubStatus' vì nó gây lỗi build trong môi trường của bạn
            }
        }
    }

    // ================================
    //        THÔNG TIN PHIM
    // ================================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.header-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("img.c13")?.attr("src")
        val plot = document.selectFirst("div.wiew_info p")?.text()
        // val metaDesc = document.selectFirst("meta[name=description]")?.attr("content") // Đã xoá
        
        // SỬA: Đã loại bỏ hoàn toàn logic 'actors' gây lỗi
        // val actors = ... 

        val genre = document.selectFirst("p.fontf1:contains(THỂ LOẠI:) span.fontf8")?.text()?.trim()

        val episodes = document.select("div.num_film a[href*=/xem/tap-]")
            .mapNotNull { a ->
                val epHref = fixUrl(a.attr("href"))
                val epName = a.text().trim()
                val epNum = epName.split("-").firstOrNull()?.toIntOrNull()

                newEpisode(epHref) {
                    name = "Tập $epName"
                    episode = epNum
                }
            }

        if (episodes.isNotEmpty()) {
            // Dùng newTvSeriesLoadResponse
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                // this.actors = actors // Đã xoá
                // Dùng 'tags' (đúng chuẩn MovieLoadResponse)
                this.tags = if (genre != null) listOf(genre) else null
            }
        } else {
            val movieWatchLink = document.selectFirst("div.playphim a")?.attr("href") ?: url
            
            // Dùng newMovieLoadResponse
            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(movieWatchLink)) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                // this.actors = actors // Đã xoá
                // Dùng 'tags' (đúng chuẩn MovieLoadResponse)
                this.tags = if (genre != null) listOf(genre) else null
            }
        }
    }

    // ================================
    //         LẤY LINK XEM
    // ================================

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("div.num_film2 a").amap { server -> 
            val serverUrl = fixUrl(server.attr("href"))
            val serverName = server.text()
            
            try {
                val serverDoc = app.get(serverUrl).document
                val script = serverDoc.select("script:contains(channel_fix)").html()
                val channelFix = Regex("""var channel_fix = "(.*?)";""").find(script)?.groupValues?.get(1)
                val domainData = Regex("""var domain_data = "(.*?)";""").find(script)?.groupValues?.get(1)

                if (channelFix != null && domainData != null) {
                    val hlsUrl = "$domainData/$channelFix.m3u8"

                    val link = newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8 
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                    callback(link)
                }
            } catch (e: Exception) {
                // Bỏ qua nếu server bị lỗi
            }
        }

        return true
    }

    // ================================
    //           HÀM HỖ TRỢ
    // ================================

    private fun fixUrl(url: String?): String {
        return when {
            url.isNullOrBlank() -> ""
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }
}
