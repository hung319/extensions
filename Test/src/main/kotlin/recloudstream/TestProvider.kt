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

    // Hàm phân tích cú pháp cho mỗi item phim
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val title = this.selectFirst(".name a")?.text() ?: linkElement.attr("title") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
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
        
        // Lấy danh sách tập phim từ các thẻ <a> trong .page-tap
        val episodes = document.select(".page-tap li a").mapNotNull {
            val epHref = it.attr("href")
            val epName = it.attr("title")?.ifEmpty { "Tập ${it.text()}" } ?: "Tập ${it.text()}"
            
            // SỬA LỖI: Sử dụng hàm newEpisode thay vì constructor đã lỗi thời
            newEpisode(epHref) {
                this.name = epName
            }
        }

        // Nếu không có danh sách tập -> Phim lẻ
        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        } else { // Nếu có -> Phim bộ
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
        data: String, // Đây là URL của tập phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy toàn bộ nội dung HTML của trang xem phim
        val response = app.get(data)
        
        // Sử dụng Regex để tìm link video trong script của JWPlayer
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
        } ?: return false // Nếu không tìm thấy link, báo lỗi

        return true
    }
}
