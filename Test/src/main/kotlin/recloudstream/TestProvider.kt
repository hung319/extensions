package recloudstream

// FIX: Cập nhật lại toàn bộ import cho chính xác
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApiKt.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class SieuTamPhimProvider : MainAPI() {
    override var name = "Siêu Tầm Phim"
    override var mainUrl = "https://www.sieutamphim.org"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // FIX: app.get bây giờ sẽ được nhận diện đúng
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()
        
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

    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a.plain") ?: return null
        val href = linkTag.attr("href")
        val title = linkTag.attr("aria-label")
                      .replaceAfter("– Status:", "").removeSuffix("– Status:")
                      .trim()

        val posterUrl = this.selectFirst("img")?.attr("src")
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document
        return document.select(".col.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replaceAfter("– Status:", "")?.removeSuffix("– Status:")?.trim()
            ?: throw ErrorLoadingException("Không thể lấy tiêu đề")

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val plotJson = document.selectFirst("div.title-movie-information")?.attr("data-description")
        val plot = try {
            plotJson?.let { jsonString ->
                // FIX: Thay thế GSON.fromJson bằng app.parseJson an toàn hơn
                val list = parseJson<List<String>>(jsonString)
                list.filter { it != "br" }.joinToString("\n")
            }
        } catch (e: Exception) {
            document.selectFirst("meta[property=og:description]")?.attr("content")
        }

        val encodedEpisodesJson = document.selectFirst(".episodeGroup")?.attr("data-episodes")
            ?: throw ErrorLoadingException("Không tìm thấy danh sách tập phim mã hóa")
        val episodeNamesJson = document.selectFirst(".panelz")?.attr("data-episode-container")
            ?: throw ErrorLoadingException("Không tìm thấy tên tập phim")

        // FIX: Sử dụng app.parseJson
        val encodedEpisodes = parseJson<List<List<String>>>(encodedEpisodesJson)
        val episodeNames = parseJson<List<String>>(episodeNamesJson).filter { it != "br" }

        val episodes = encodedEpisodes.mapIndexedNotNull { index, encodedPair ->
            val encodedUrl = encodedPair.getOrNull(0) ?: return@mapIndexedNotNull null
            val epNum = encodedPair.getOrNull(1)?.toIntOrNull()
            val epName = episodeNames.getOrNull(index) ?: "Tập $epNum"

            // FIX: Sử dụng hàm newEpisode thay vì constructor đã cũ
            newEpisode(encodedUrl) {
                this.name = epName
                this.episode = epNum
            }
        }

        // FIX: episodes.size bây giờ sẽ hoạt động bình thường
        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            // FIX: episodes.firstOrNull()?.data cũng sẽ hoạt động
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }
    }
    
    private fun decodeUrl(encoded: String): String {
        var result = ""
        for (char in encoded) {
            result += when (char) {
                '~' -> '0'; '!' -> '1'; '@' -> '2'; '#' -> '3'; '$' -> '4'
                '%' -> '5'; '^' -> '6'; '&' -> '7'; '*' -> '8'; '(' -> '9'
                else -> char
            }
        }
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val decodedUrl = decodeUrl(data)
        val document = app.get(decodedUrl, referer = mainUrl).document

        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        val sourceLinks = if (iframeSrc != null) {
            app.get(iframeSrc, referer = decodedUrl).document.select("source, video")
        } else {
            document.select("source, video")
        }

        sourceLinks.apmap {
            val videoUrl = it.attr("src")
            if (videoUrl.isNotBlank()) {
                // FIX: loadExtractor bây giờ sẽ được nhận diện đúng
                loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
