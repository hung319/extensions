package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.reCAPTCHAv3
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

// import com.lagradost.cloudstream3.utils.ExtractorApiKt.newExtractorLink

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

    // Định nghĩa các mục trên trang chủ
    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi-vn2-phim-2vn-phim/new.aspx" to "Phim Mới Cập Nhật",
        "$mainUrl/danh-muc/7/trung-quoc.aspx" to "Phim Trung Quốc",
        "$mainUrl/danh-muc/10/han-quoc.aspx" to "Phim Hàn Quốc",
        "$mainUrl/danh-muc/8/thai-lan.aspx" to "Phim Thái Lan",
        "$mainUrl/the-loai/29/hoat-hinh-anime.aspx" to "Phim Hoạt Hình"
    )

    // Tải dữ liệu cho các mục trang chủ
    override suspend fun mainPageLoad(
        page: Int,
        mainPageData: MainPageData
    ): HomePageResponse {
        val url = if (page == 1) mainPageData.data else "${mainPageData.data}?page=$page"
        val document = app.get(url).document
        
        // Trang chủ và trang danh mục dùng chung cấu trúc `div.Form2`
        val items = document.select("div.Form2").mapNotNull {
            parseMovieCard(it)
        }
        
        return newHomePageResponse(mainPageData.name, items)
    }

    // Hàm chung để parse thẻ phim (dùng cho trang chủ, danh mục)
    private fun parseMovieCard(element: Element): SearchResponse? {
        val a = element.selectFirst("p.Form2Text a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.text()
        val img = element.selectFirst("img.c10")?.attr("src")
        val episode = element.selectFirst("div.thongtintapphim span.boxtt_tap")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.TvSeries) { // Mặc định là TvSeries, sẽ check lại ở `load`
            posterUrl = fixUrl(img)
            if (!episode.isNullOrEmpty()) {
                addDubStatus(true, episode) // Hiển thị thông tin tập
            }
        }
    }

    // ================================
    //           TÌM KIẾM
    // ================================

    override suspend fun search(query: String): List<SearchResponse> {
        // Trang tìm kiếm dùng URL: /tim-kiem/ten-phim.aspx
        val url = "$mainUrl/tim-kiem/$query.aspx"
        val document = app.get(url).document

        // Trang tìm kiếm dùng cấu trúc `div.boxtk`
        return document.select("div.boxtk").mapNotNull {
            val a = it.selectFirst("p.nametk a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = a.text()
            val img = it.selectFirst("img.c10")?.attr("src")
            val status = it.selectFirst("div.taptk")?.text()?.replace("Tập phim", "")?.trim()

            newMovieSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrl(img)
                if (status != null) {
                    addDubStatus(true, status)
                }
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

        // Lấy thông tin diễn viên từ meta description
        val metaDesc = document.selectFirst("meta[name=description]")?.attr("content")
        val actors = metaDesc?.split(".").lastOrNull()?.trim()
            ?.split(" - ")
            ?.map { it.trim() }
            ?.let { if (it.size > 1) it else null } // Chỉ lấy nếu có nhiều diễn viên

        val genre = document.selectFirst("p.fontf1:contains(THỂ LOẠI:) span.fontf8")?.text()?.trim()

        // Lấy danh sách tập
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

        // Kiểm tra là Phim Lẻ hay Phim Bộ
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.actors = actors
                addGenre(genre)
            }
        } else {
            // Nếu không có danh sách tập, đây là phim lẻ. Link xem phim là chính trang này.
            // Tuy nhiên, trang info (`/xem/681/...`) lại link đến trang xem phim (`/xem/tap-1/...`)
            // Ta sẽ lấy link "XEM PHIM" làm link xem phim lẻ
            val movieWatchLink = document.selectFirst("div.playphim a")?.attr("href") ?: url
            
            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(movieWatchLink)) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.actors = actors
                addGenre(genre)
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

        // Trang này có nhiều server, ta sẽ duyệt qua tất cả
        // `apmap` sẽ chạy song song các request
        document.select("div.num_film2 a").apmap { server ->
            val serverUrl = fixUrl(server.attr("href"))
            val serverName = server.text()
            
            try {
                val serverDoc = app.get(serverUrl).document
                
                // Tìm script chứa thông tin link
                val script = serverDoc.select("script:contains(channel_fix)").html()

                // Dùng Regex để trích xuất các biến JavaScript quan trọng
                val channelFix = Regex("""var channel_fix = "(.*?)";""").find(script)?.groupValues?.get(1)
                val domainData = Regex("""var domain_data = "(.*?)";""").find(script)?.groupValues?.get(1)

                if (channelFix != null && domainData != null) {
                    // *** PHẦN QUAN TRỌNG ***
                    // Dựa trên phân tích, trang web dùng JWPlayer và các biến JS
                    // `domain_data` là CDN, `channel_fix` là ID của video.
                    // Cấu trúc link HLS (m3u8) khả năng cao là: {domain_data}/{channel_fix}.m3u8
                    
                    val hlsUrl = "$domainData/$channelFix.m3u8"

                    // **ĐÃ CẬP NHẬT THEO YÊU CẦU**
                    // Sử dụng ExtractorLinkType.M3U8
                    val link = newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8 // Cập nhật: Dùng M3U8 thay vì Hls
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                    // Gửi link đã tạo qua callback
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

    // Sửa lỗi URL (thêm domain, https)
    private fun fixUrl(url: String?): String {
        return when {
            url.isNullOrBlank() -> ""
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }
}
