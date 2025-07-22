package com.recloudstream.sieutamphim

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.util.Base64

class SieuTamPhimProvider : MainAPI() {
    // Thông tin cơ bản của provider
    override var mainUrl = "https://www.sieutamphim.org"
    override var name = "Siêu Tầm Phim"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "vi"
    override val hasMainPage = true

    // Hàm helper để parse một item phim (dùng chung cho trang chủ và tìm kiếm)
    private fun parseSearchResult(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = element.selectFirst("h5.post-title a")?.text()?.substringBefore("– Status:")?.trim() ?: return null
        val posterUrl = fixUrlNull(element.selectFirst("div.box-image img")?.attr("src"))
        
        val tvType = if (href.contains("/phim-bo/")) TvType.TvSeries else TvType.Movie

        // FIX: Đổi thành newMovieSearchResponse
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    // Hàm để lấy danh sách phim trên trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.title-source").forEach { sectionTitle ->
            try {
                val title = sectionTitle.selectFirst("h2")?.text() ?: "Unknown"
                // FIX: Thêm dấu ngoặc () cho các hàm parent()
                val filmListContainer = sectionTitle.parent()?.nextElementSibling()?.selectFirst(".row.slider") 
                                        ?: sectionTitle.parent()?.parent()?.nextElementSibling()?.selectFirst(".row")
                
                filmListContainer?.let {
                    val movies = it.select("div.col.post-item").mapNotNull { element ->
                        parseSearchResult(element)
                    }
                    if (movies.isNotEmpty()) {
                        homePageList.add(HomePageList(title, movies))
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua nếu có lỗi ở một section
            }
        }
        
        return HomePageResponse(homePageList)
    }

    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.col.post-item").mapNotNull { element ->
            parseSearchResult(element)
        }
    }

    // Hàm lấy thông tin chi tiết của phim/series
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore("– Status:")?.trim() ?: return null
        val posterUrl = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select("div.the-tim-kiem a[rel=tag]")?.map { it.text() }
        val yearTag = tags?.firstOrNull { it.matches(Regex("\\d{4}")) }
        val year = yearTag?.toIntOrNull()

        val episodeDataString = document.selectFirst("div.button-group.episodeGroup")
            ?.attr("data-episodes") ?: return null

        val episodeRegex = Regex("""\{"([^"]+)","([^"]+)"\}""")
        val episodes = episodeRegex.findAll(episodeDataString).mapNotNull { matchResult ->
            val (obfuscatedData, episodeNum) = matchResult.destructured
            // FIX: Sử dụng newEpisode thay vì constructor cũ
            newEpisode(obfuscatedData) {
                this.name = "Tập $episodeNum"
                this.episode = episodeNum.toIntOrNull()
            }
        }.toList()

        if (episodes.isEmpty()) {
             return newMovieLoadResponse(title, url, TvType.Movie, episodeDataString) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
        
        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    private fun encodeToExactBase64(input: String): String {
        val urlEncoded = URLEncoder.encode(input, "UTF-8")
                            .replace(Regex("%([0-9A-F]{2})")) {
                                it.groupValues[1].toInt(16).toChar().toString()
                            }
        return Base64.encodeToString(urlEncoded.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    
    // Hàm lấy link xem phim
    // FIX: Sửa lại signature của hàm cho đúng với MainAPI
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit // Sửa từ Boolean -> Unit
    ): Boolean {
        val obfuscatedData = data
        val encodedData = encodeToExactBase64(obfuscatedData)
        val embedUrl = "$mainUrl/embed.html?url=$encodedData"
        
        val embedDoc = app.get(embedUrl, referer = mainUrl).document
        val scriptContent = embedDoc.select("script").firstOrNull { it.data().contains("sources") }?.data()
            ?: return false
            
        val streamUrlRegex = Regex("""file:\s*"(https?://[^"]+\.m3u8)"""")
        val streamUrl = streamUrlRegex.find(scriptContent)?.groupValues?.get(1) ?: return false

        callback( // Bỏ .invoke đi cho gọn
            ExtractorLink(
                source = this.name,
                name = "Siêu Tầm Phim Server",
                url = streamUrl,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8 
            )
        )

        return true
    }
}
