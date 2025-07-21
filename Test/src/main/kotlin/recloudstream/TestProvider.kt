package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Định nghĩa lớp Provider chính, kế thừa từ MainAPI
class MotchillProvider : MainAPI() {
    // Ghi đè các thuộc tính cơ bản của provider
    override var name = "Motchill"
    override var mainUrl = "https://www.motchill97.com"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Hàm lấy danh sách các mục trên trang chính (Phim mới, Phim lẻ, Phim bộ, v.v.)
    override val mainPage = mainPageOf(
        "$mainUrl/phim-moi" to "Phim Mới Cập Nhật",
        "$mainUrl/phim-le" to "Phim Lẻ",
        "$mainUrl/phim-bo" to "Phim Bộ",
        "$mainUrl/the-loai/phim-chieu-rap" to "Phim Chiếu Rạp"
    )

    // SỬA LỖI: Cập nhật hàm toSearchResponse để lấy poster chính xác
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".name a")?.text() ?: this.selectFirst("a")?.attr("title") ?: return null
        
        val img = this.selectFirst("img")
        var posterUrl: String? = null

        // Logic lấy poster mới, ưu tiên các nguồn đáng tin cậy hơn
        // 1. Ưu tiên lấy URL từ thuộc tính "onerror" vì đây là link dự phòng của chính trang web.
        val onerrorAttr = img?.attr("onerror")
        if (!onerrorAttr.isNullOrBlank()) {
            // Dùng Regex để trích xuất URL từ chuỗi: "this.onerror=null;this.src='URL_Cần_Lấy'"
            posterUrl = Regex("""this\.src='([^']+)""").find(onerrorAttr)?.groupValues?.get(1)
        }

        // 2. Nếu không có onerror, thử lấy từ data-src (cho lazy-loading).
        if (posterUrl.isNullOrBlank()) {
            posterUrl = img?.attr("data-src")
        }

        // 3. Nếu vẫn không có, lấy từ src như một phương án cuối cùng.
        if (posterUrl.isNullOrBlank()) {
            posterUrl = img?.attr("src")
        }

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }
    
    // Hàm thực hiện tải trang chính và phân tích cú pháp
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("ul.list-film > li").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    // Hàm thực hiện tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/$query"
        val document = app.get(searchUrl).document
        return document.select("ul.list-film > li").mapNotNull {
            it.toSearchResponse()
        }
    }

    // Hàm tải thông tin chi tiết của một phim (hoặc phim bộ)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.movie-title .title-1")?.text()?.trim() ?: "Không tìm thấy tiêu đề"
        val poster = document.selectFirst(".poster img")?.attr("src")
        val description = document.selectFirst(".detail-content-main")?.text()?.trim()
        val yearText = document.selectFirst("span.title-year")?.text()?.removeSurrounding("(", ")")
        val year = yearText?.toIntOrNull()
        
        val episodes = document.select(".page-tap li a").mapNotNull {
            val epHref = it.attr("href")
            val epName = it.attr("title")?.ifEmpty { "Tập ${it.text()}" } ?: "Tập ${it.text()}"
            
            newEpisode(epHref) {
                this.name = epName
            }
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        }
    }

    // Regex để tìm link video .m3u8 trong mã nguồn của trang
    private val jwplayerSourceRegex = Regex("""sources:\s*\[\s*\{file:\s*"(.*?m3u8.*?)"""")

    // Hàm quan trọng nhất: Lấy link video trực tiếp (m3u8) để phát
    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        
        jwplayerSourceRegex.find(response.text)?.groupValues?.get(1)?.let { link ->
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = link,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        } ?: return false

        return true
    }
}
