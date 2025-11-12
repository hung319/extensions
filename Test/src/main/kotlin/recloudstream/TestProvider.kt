package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.text.Normalizer // Thêm import để khử dấu

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
    //         HÀM HỖ TRỢ KHỬ DẤU
    // ================================

    // Hàm để loại bỏ dấu tiếng Việt
    private fun deAccent(str: String): String {
        val nfdNormalizedString = Normalizer.normalize(str, Normalizer.Form.NFD) 
        val pattern = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        return pattern.replace(nfdNormalizedString, "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
    }

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
        try {
            val data = request.data
            val url = if (page == 1) "$mainUrl/$data" else "$mainUrl/$data?page=$page"
            
            val document = app.get(url).document
            
            val items = document.select("div.Form2").mapNotNull { element ->
                val a = element.selectFirst("p.Form2Text a") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                val title = a.text()
                val img = element.selectFirst("img.c10")?.attr("src")

                // Dùng newMovieSearchResponse (chuẩn MainAPI của bạn)
                newMovieSearchResponse(title, href, TvType.TvSeries) { 
                    posterUrl = fixUrl(img)
                }
            }
            
            return newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            println("Vn2Provider ERROR in getMainPage: ${e.message}")
            throw e // Ném lỗi để debug
        }
    }

    // ================================
    //           TÌM KIẾM
    // ================================

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            // SỬA: Thêm .lowercase() và thay " " bằng "-"
            val deAccentedQuery = deAccent(query)
            val formattedQuery = deAccentedQuery.replace(" ", "-").lowercase()
            val url = "$mainUrl/tim-kiem/$formattedQuery.aspx"
            println("Vn2Provider Searching URL: $url") // Log URL tìm kiếm
            
            val document = app.get(url).document

            val results = document.select("div.boxtk").mapNotNull {
                val a = it.selectFirst("p.nametk a") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                val title = a.text()
                val img = it.selectFirst("img.c10")?.attr("src")

                // Dùng newMovieSearchResponse (chuẩn MainAPI của bạn)
                newMovieSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = fixUrl(img)
                }
            }
            println("Vn2Provider Search found ${results.size} items") // Log số kết quả
            return results
        } catch (e: Exception) {
            println("Vn2Provider ERROR in search: ${e.message}")
            throw e // Ném lỗi để debug
        }
    }

    // ================================
    //        THÔNG TIN PHIM
    // ================================

    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url).document

            val title = document.selectFirst("h1.header-title")?.text()?.trim() ?: ""
            val poster = document.selectFirst("img.c13")?.attr("src")
            val plot = document.selectFirst("div.wiew_info p")?.text()
            
            // Đã loại bỏ 'actors' theo yêu cầu
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
            println("Vn2Provider Load found ${episodes.size} episodes") // Log số tập

            if (episodes.isNotEmpty()) {
                // Dùng newTvSeriesLoadResponse
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = plot
                    // Dùng 'tags' (đúng chuẩn MovieLoadResponse của bạn)
                    this.tags = if (genre != null) listOf(genre) else null
                }
            } else {
                val movieWatchLink = document.selectFirst("div.playphim a")?.attr("href") ?: url
                
                // Dùng newMovieLoadResponse
                return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(movieWatchLink)) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = plot
                    // Dùng 'tags' (đúng chuẩn MovieLoadResponse của bạn)
                    this.tags = if (genre != null) listOf(genre) else null
                }
            }
        } catch (e: Exception) {
            println("Vn2Provider ERROR in load: ${e.message}")
            throw e // Ném lỗi để debug
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

        println("Vn2Provider loadLinks started for data: $data")
        val document = app.get(data).document
        var linkFound = false

        // Xử lý song song tất cả các server (Server 1, Server 2...)
        document.select("div.num_film2 a").amap { server -> 
            try {
                val serverUrl = fixUrl(server.attr("href"))
                val serverName = server.text()
                println("Vn2Provider trying server: $serverName, URL: $serverUrl")
                
                val serverDoc = app.get(serverUrl).document
                
                // Lấy toàn bộ HTML (vì .select(script) đã thất bại)
                val script = serverDoc.html() 
                println("Vn2Provider script length: ${script.length}")

                // --- Bắt đầu trích xuất biến (ĐÃ SỬA LẠI REGEX) ---
                
                // SỬA 1: Regex cụ thể hơn cho channel_fix (khớp với ID dài)
                val channelFix = Regex("""var channel_fix = "([a-zA-Z0-9_-]+)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                println("Vn2Provider channelFix: $channelFix")

                val channel2Fix = Regex("""var channel2_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channel3Fix = Regex("""var channel3_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channelFixIframe = Regex("""var channel_fix_iframe = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                
                val nameTapPhim = Regex("""var name_tapphim = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                println("Vn2Provider nameTapPhim: $nameTapPhim")
                
                // SỬA 2: Lấy 'name_idphim' thay vì 'name_fixid'
                val nameIdPhim = Regex("""var name_idphim = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                println("Vn2Provider nameIdPhim: $nameIdPhim")

                // SỬA 3: Bỏ qua 'totalView' vì nó được tạo động
                // val totalView = ... 
                
                val nameFixload = Regex("""var name_fixload = '(.*?)';""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val domainApi = Regex("""var domain_api = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: "https://vn2data.com/play"
                // --- Kết thúc trích xuất biến ---

                // Kiểm tra xem các biến quan trọng có rỗng không
                if (channelFix.isBlank() || nameTapPhim.isBlank() || nameIdPhim.isBlank()) {
                    println("Vn2Provider FAILED: Critical variables are blank. Skipping server.")
                    return@amap // Bỏ qua vòng lặp này
                }

                val numSv = "111" 
                // SỬA 4: Xây dựng idplay mà không cần totalView
                val idplay = "${nameTapPhim}sv${numSv}id${nameIdPhim}numviewchankeynull" 
                println("Vn2Provider idplay: $idplay")

                val contentLink1 = channelFix.replace("V8FfiTt0053896624711HQ2", "")
                
                var channelVideoEmbed = "$domainApi/js_fix/9x/play.php?link=${idplay}vn2fix1${contentLink1}vn2fix2${channel2Fix}vn2fix3${channelFixIframe}vn2fixload${channel3Fix}vn2fixurl4vn2fixurl56O548l721190cookiecatvn2myname${nameFixload}folderfixcatnumget$serverUrl"
                
                val iframeUrl = channelVideoEmbed.replace("&", "vn2_fix")
                println("Vn2Provider constructed iframeUrl: $iframeUrl")

                val iframeResponseText = app.get(iframeUrl, referer = serverUrl).text
                println("Vn2Provider iframeResponseText length: ${iframeResponseText.length}")

                // Regex quan trọng (giữ nguyên)
                val videoUrl = Regex("""var link_video_sd = "(https?://.*?)";""").find(iframeResponseText)?.groupValues?.get(1)
                println("Vn2Provider extracted videoUrl: $videoUrl")

                if (videoUrl != null && videoUrl.isNotBlank()) {
                    println("Vn2Provider SUCCESS: Link found!")
                    val linkType = if (videoUrl.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO 
                    }

                    callback(newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = videoUrl, 
                        type = linkType 
                    ) {
                        this.referer = "$mainUrl/" 
                        this.quality = Qualities.Unknown.value
                    })
                    linkFound = true
                } else {
                    println("Vn2Provider FAILED: videoUrl regex did not match.")
                }
            } catch (e: Exception) {
                println("Vn2Provider ERROR in loadLinks amap loop: ${e.message}")
                e.printStackTrace() // In đầy đủ stack trace
                throw e // Ném lỗi để debug
            }
        }

        println("Vn2Provider loadLinks finished. linkFound: $linkFound")
        return linkFound
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
