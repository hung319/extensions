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
        data: String, // data ở đây là URL đến trang xem phim (ví dụ: /xem/tap-1/...)
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        var linkFound = false

        // Xử lý song song tất cả các server (Server 1, Server 2...)
        document.select("div.num_film2 a").amap { server -> 
            val serverUrl = fixUrl(server.attr("href"))
            val serverName = server.text()
            
            try {
                // 1. Tải trang của server (Server 1, Server 2, ...)
                val serverDoc = app.get(serverUrl).document
                
                // 2. Tìm script chứa các biến cần thiết (giống như trong content.js)
                val script = serverDoc.select("script:contains(channel_fix)").html()

                // 3. Trích xuất tất cả các biến cần thiết để xây dựng link iframe
                val channelFix = Regex("""var channel_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channel2Fix = Regex("""var channel2_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channel3Fix = Regex("""var channel3_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channelFixIframe = Regex("""var channel_fix_iframe = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                
                val nameTapPhim = Regex("""var name_tapphim = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                
                // SỬA 1: Dùng .replace(Regex, "")
                val nameFixId = Regex("""var name_fixid = Number\((.*?)\);""").find(script)?.groupValues?.get(1)?.replace("\"".toRegex(), "")?.trim() ?: ""
                val totalView = Regex("""var totalview = (.*?);""").find(script)?.groupValues?.get(1)?.replace("\"".toRegex(), "")?.trim() ?: ""
                
                val nameFixload = Regex("""var name_fixload = '(.*?)';""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val domainApi = Regex("""var domain_api = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: "https://vn2data.com/play"

                // 4. Xây dựng 'idplay' (theo logic của content.js)
                val numSv = "111" // Giả định '111' là đủ
                val idplay = "${nameTapPhim}sv${numSv}id${nameFixId}numview${totalView}chankeynull" 

                // 5. Xây dựng URL iframe trỏ đến 'play.php' (theo logic của content.js)
                val contentLink1 = channelFix.replace("V8FfiTt0053896624711HQ2", "")
                
                var channelVideoEmbed = "$domainApi/js_fix/9x/play.php?link=${idplay}vn2fix1${contentLink1}vn2fix2${channel2Fix}vn2fix3${channelFixIframe}vn2fixload${channel3Fix}vn2fixurl4vn2fixurl56O548l721190cookiecatvn2myname${nameFixload}folderfixcatnumget$serverUrl"
                
                val iframeUrl = channelVideoEmbed.replace("&", "vn2_fix")

                // 6. Tải nội dung text của trang iframe (play.php)
                val iframeResponseText = app.get(iframeUrl, referer = serverUrl).text

                // 7. Dùng regex để trích xuất 'link_video_sd'
                val videoUrl = Regex("""var link_video_sd = "(.*?)";""").find(iframeResponseText)?.groupValues?.get(1)

                if (videoUrl != null && videoUrl.isNotBlank()) {
                    // 8. Xác định loại link (MP4 hay M3U8)
                    val linkType = if (videoUrl.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        // SỬA 2: Dùng .VIDEO (viết hoa)
                        ExtractorLinkType.VIDEO 
                    }

                    // 9. Gửi link về cho trình phát
                    callback(newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = videoUrl, // Link .mp4
                        type = linkType // Loại .VIDEO
                    ) {
                        this.referer = "$mainUrl/" // Referer chung
                        this.quality = Qualities.Unknown.value
                    })
                    linkFound = true
                }
            } catch (e: Exception) {
                // Bỏ qua nếu server này bị lỗi
            }
        }

        return linkFound // Trả về true nếu tìm thấy ít nhất 1 link
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
