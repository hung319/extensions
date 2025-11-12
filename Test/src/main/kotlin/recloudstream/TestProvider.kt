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

    // Hàm để loại bỏ dấu tiếng Việt (Hàm này đã đúng)
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
            
            val document = app.get(url).document

            return document.select("div.boxtk").mapNotNull {
                val a = it.selectFirst("p.nametk a") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                val title = a.text()
                val img = it.selectFirst("img.c10")?.attr("src")

                // Dùng newMovieSearchResponse (chuẩn MainAPI của bạn)
                newMovieSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = fixUrl(img)
                }
            }
        } catch (e: Exception) {
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

        val document = app.get(data).document
        var linkFound = false

        // Xử lý song song tất cả các server (Server 1, Server 2...)
        document.select("div.num_film2 a").amap { server -> 
            try {
                val serverUrl = fixUrl(server.attr("href"))
                val serverName = server.text()
                
                val serverDoc = app.get(serverUrl).document
                val script = serverDoc.select("script:contains(channel_fix)").html()

                val channelFix = Regex("""var channel_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channel2Fix = Regex("""var channel2_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channel3Fix = Regex("""var channel3_fix = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val channelFixIframe = Regex("""var channel_fix_iframe = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                
                val nameTapPhim = Regex("""var name_tapphim = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                
                val nameFixId = Regex("""var name_fixid = Number\((.*?)\);""").find(script)?.groupValues?.get(1)?.replace("\"".toRegex(), "")?.trim() ?: ""
                val totalView = Regex("""var totalview = (.*?);""").find(script)?.groupValues?.get(1)?.replace("\"".toRegex(), "")?.trim() ?: ""
                
                val nameFixload = Regex("""var name_fixload = '(.*?)';""").find(script)?.groupValues?.get(1)?.trim() ?: ""
                val domainApi = Regex("""var domain_api = "(.*?)";""").find(script)?.groupValues?.get(1)?.trim() ?: "https://vn2data.com/play"

                val numSv = "111" 
                val idplay = "${nameTapPhim}sv${numSv}id${nameFixId}numview${totalView}chankeynull" 

                val contentLink1 = channelFix.replace("V8FfiTt0053896624711HQ2", "")
                
                var channelVideoEmbed = "$domainApi/js_fix/9x/play.php?link=${idplay}vn2fix1${contentLink1}vn2fix2${channel2Fix}vn2fix3${channelFixIframe}vn2fixload${channel3Fix}vn2fixurl4vn2fixurl56O548l721190cookiecatvn2myname${nameFixload}folderfixcatnumget$serverUrl"
                
                val iframeUrl = channelVideoEmbed.replace("&", "vn2_fix")

                val iframeResponseText = app.get(iframeUrl, referer = serverUrl).text

                // SỬA: Dùng regex cụ thể hơn, tìm kiếm 'http' bên trong dấu ngoặc kép
                // để tránh khớp với 'var link_video_sd = "";'
                val videoUrl = Regex("""var link_video_sd = "(https?://.*?)";""").find(iframeResponseText)?.groupValues?.get(1)

                if (videoUrl != null && videoUrl.isNotBlank()) {
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
                }
            } catch (e: Exception) {
                throw e // Ném lỗi để debug
            }
        }

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
