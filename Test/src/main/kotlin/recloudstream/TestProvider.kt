package recloudstream // Package vẫn giữ theo yêu cầu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Khai báo lớp provider
class Kurakura21Provider : MainAPI() {
    override var mainUrl = "https://kurakura21.net"
    override var name = "Kurakura21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    
    private data class EpisodeData(
        @JsonProperty("ajaxUrl") val ajaxUrl: String,
        @JsonProperty("postData") val postData: Map<String, String>
    )

    // THAY ĐỔI 1: Chuyển hàm toSearchResult thành một hàm riêng tư bình thường
    // thay vì một hàm mở rộng cho Element.
    private fun parseSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("h2.entry-title a")?.text() ?: return null
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = element.selectFirst("img")?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(element.selectFirst(".gmr-quality-item a")?.text())
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.home-widget").forEach { block ->
            val header = block.selectFirst("h3.homemodule-title")?.text() ?: return@forEach
            // Gọi hàm mới
            val movies = block.select("article, div.gmr-item-modulepost").mapNotNull {
                parseSearchResult(it)
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(header, movies))
            }
        }
        
        val latestMoviesHeader = document.selectFirst("#primary h3.homemodule-title")?.text() ?: "Latest Movies"
        val latestMovies = document.select("#gmr-main-load article.item-infinite").mapNotNull {
            parseSearchResult(it) // Gọi hàm mới
        }
        if(latestMovies.isNotEmpty()) {
            homePageList.add(HomePageList(latestMoviesHeader, latestMovies))
        }

        return HomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item-infinite").mapNotNull { parseSearchResult(it) } // Gọi hàm mới
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("figure.pull-left img")?.attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val year = document.selectFirst("div.gmr-moviedata")?.text()?.let {
            Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        val postId = document.body().attr("class").let {
            Regex("postid-(\\d+)").find(it)?.groupValues?.get(1)
        } ?: throw ErrorLoadingException("Failed to get post ID")

        val servers = document.select("ul.muvipro-player-tabs li a")

        val episodes = servers.mapIndexed { index, server ->
            val serverName = server.text()
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val postData = mapOf(
                "action" to "muvipro_player_content",
                "post_id" to postId,
                "player" to (index + 1).toString()
            )
            
            // THAY ĐỔI 2: Gọi trực tiếp AppUtils.toJson
            val episodeDataJson = AppUtils.toJson(EpisodeData(ajaxUrl, postData))

            Episode(data = episodeDataJson, name = serverName)
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // THAY ĐỔI 3: Gọi trực tiếp AppUtils.parseJson
        val episodeData = AppUtils.parseJson<EpisodeData>(data)
        val ajaxResponse = app.post(episodeData.ajaxUrl, data = episodeData.postData).document
        val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("Failed to find iframe source")
            
        return loadExtractor(iframeSrc, subtitleCallback, callback)
    }
}
