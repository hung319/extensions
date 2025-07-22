package recloudstream

import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.app
import com.lagradost.cloudstream3.utils.ExtractorApiKt.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities.getQualityFromString
import org.jsoup.nodes.Element

class SieuTamPhimProvider : MainAPI() { // Kế thừa từ MainAPI để tạo provider
    // Thông tin cơ bản về plugin
    override var name = "Siêu Tầm Phim"
    override var mainUrl = "https://www.sieutamphim.org"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Hàm lấy danh sách phim hiển thị trên trang chủ của plugin
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // Lấy các section phim trên trang chủ, ví dụ "PHIM MỚI"
        val mainSections = document.select("section.section")
        
        mainSections.forEach { section ->
            val title = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            val movies = section.select(".col.post-item").mapNotNull {
                it.toSearchResult()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }
        
        return HomePageResponse(homePageList)
    }

    // Hàm chuyển đổi một phần tử HTML thành đối tượng SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a.plain") ?: return null
        val href = linkTag.attr("href")
        val title = linkTag.attr("aria-label") // Tiêu đề đầy đủ nằm trong aria-label
                      .replaceAfter("– Status:", "").removeSuffix("– Status:")
                      .trim()

        val posterUrl = this.selectFirst("img")?.attr("src")

        // Heuristic để xác định loại phim dựa trên URL hoặc tiêu đề
        val isTvSeries = href.contains("/phim-bo/") || title.contains(Regex("Tập|Phần|Season"))

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Hàm thực hiện tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document

        // Phân tích kết quả tìm kiếm từ HTML, cấu trúc tương tự trang chủ
        return document.select(".col.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm tải thông tin chi tiết của một phim/series
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Lấy thông tin từ thẻ meta để đảm bảo độ chính xác
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replaceAfter("– Status:", "")?.removeSuffix("– Status:")?.trim()
            ?: throw ErrorLoadingException("Không thể lấy tiêu đề")

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        // Lấy mô tả phim từ thuộc tính data-description
        val plotJson = document.selectFirst("div.title-movie-information")?.attr("data-description")
        val plot = try {
            // Parse a JSON-like array string to a readable description
            plotJson?.let { jsonString ->
                val list = GSON.fromJson<List<String>>(jsonString, object : TypeToken<List<String>>() {}.type)
                list.filter { it != "br" }.joinToString("\n")
            }
        } catch (e: Exception) {
            document.selectFirst("meta[property=og:description]")?.attr("content")
        }


        // **PHẦN QUAN TRỌNG: LẤY VÀ GHÉP TẬP PHIM**
        // Lấy link đã mã hóa
        val encodedEpisodesJson = document.selectFirst(".episodeGroup")?.attr("data-episodes")
            ?: throw ErrorLoadingException("Không tìm thấy danh sách tập phim mã hóa")

        // Lấy tên tập phim
        val episodeNamesJson = document.selectFirst(".panelz")?.attr("data-episode-container")
            ?: throw ErrorLoadingException("Không tìm thấy tên tập phim")

        // Dùng thư viện GSON để parse chuỗi JSON
        val encodedEpisodes = GSON.fromJson<List<List<String>>>(encodedEpisodesJson, object : TypeToken<List<List<String>>>() {}.type)
        val episodeNames = GSON.fromJson<List<String>>(episodeNamesJson, object : TypeToken<List<String>>() {}.type).filter { it != "br" }

        val episodes = encodedEpisodes.mapIndexedNotNull { index, encodedPair ->
            val encodedUrl = encodedPair.getOrNull(0) ?: return@mapIndexedNotNull null
            val epNum = encodedPair.getOrNull(1)?.toIntOrNull()
            
            // Ghép tên tập phim từ danh sách tên đã lấy được
            val epName = episodeNames.getOrNull(index) ?: "Tập $epNum"

            Episode(
                data = encodedUrl, // Dữ liệu truyền đi là URL đã mã hóa
                name = epName,
                episode = epNum
            )
        }

        // Kiểm tra xem đây là phim lẻ hay phim bộ
        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }
    }

    /**
     * Hàm giải mã URL từ trang web.
     * Trang web sử dụng thuật toán thay thế ký tự đơn giản.
     */
    private fun decodeUrl(encoded: String): String {
        var result = ""
        for (char in encoded) {
            result += when (char) {
                '~' -> '0'
                '!' -> '1'
                '@' -> '2'
                '#' -> '3'
                '$' -> '4'
                '%' -> '5'
                '^' -> '6'
                '&' -> '7'
                '*' -> '8'
                '(' -> '9'
                else -> char
            }
        }
        return result
    }


    // Hàm lấy link xem phim trực tiếp từ một tập phim
    override suspend fun loadLinks(
        data: String, // Đây là URL đã được mã hóa từ hàm load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // **BƯỚC 1: Giải mã URL**
        val decodedUrl = decodeUrl(data)

        // **BƯỚC 2: Tải nội dung từ URL đã giải mã**
        // URL này có thể là một trang player khác hoặc link trực tiếp
        // Thêm referer là trang phim gốc để bypass một số kiểm tra bảo mật
        val document = app.get(decodedUrl, referer = mainUrl).document

        // **BƯỚC 3: Tìm link video cuối cùng**
        // Link video có thể nằm trong iframe hoặc thẻ source
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (iframeSrc != null) {
            // Nếu có iframe, tải tiếp nội dung iframe
             app.get(iframeSrc, referer = decodedUrl).document.select("source, video").forEach {
                val videoUrl = it.attr("src")
                if (videoUrl.isNotBlank()) {
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                }
            }
        } else {
            // Nếu không có iframe, tìm trực tiếp trong trang
            document.select("source, video").forEach {
                val videoUrl = it.attr("src")
                if (videoUrl.isNotBlank()) {
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
