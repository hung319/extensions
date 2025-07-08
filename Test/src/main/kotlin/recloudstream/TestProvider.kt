package recloudstream // Package vẫn giữ theo yêu cầu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

// Khai báo lớp provider
class Kurakura21Provider : MainAPI() {
    // Thông tin cơ bản về provider
    override var mainUrl = "https://kurakura21.net"
    override var name = "Kurakura21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )
    
    // Lớp dữ liệu để lưu thông tin cho lời gọi AJAX
    private data class EpisodeData(
        @JsonProperty("ajaxUrl") val ajaxUrl: String,
        @JsonProperty("postData") val postData: Map<String, String>
    )

    // Hàm để phân tích kết quả tìm kiếm và danh sách phim
    private fun Element.toSearchResult(): SearchResponse? {
        // Đổi selectFirst() thành select().firstOrNull() để khắc phục lỗi biên dịch
        val title = this.select("h2.entry-title a").firstOrNull()?.text() ?: return null
        val href = this.select("a").firstOrNull()?.attr("href") ?: return null
        val posterUrl = this.select("img").firstOrNull()?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(this.select(".gmr-quality-item a").firstOrNull()?.text())
        }
    }
    
    // --- Phân tích trang chủ ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        document.select("div.home-widget").forEach { block ->
            val header = block.select("h3.homemodule-title").firstOrNull()?.text() ?: return@forEach
            val movies = block.select("article, div.gmr-item-modulepost").mapNotNull {
                it.toSearchResult()
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(header, movies))
            }
        }
        
        val latestMoviesHeader = document.select("#primary h3.homemodule-title").firstOrNull()?.text() ?: "Latest Movies"
        val latestMovies = document.select("#gmr-main-load article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
        if(latestMovies.isNotEmpty()) {
            homePageList.add(HomePageList(latestMoviesHeader, latestMovies))
        }

        return HomePageResponse(homePageList)
    }

    // --- Chức năng tìm kiếm ---
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
    }

    // --- Tải dữ liệu ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("h1.entry-title").firstOrNull()?.text()?.trim() ?: "No Title"
        val poster = document.select("figure.pull-left img").firstOrNull()?.attr("data-src")
        val description = document.select("div.entry-content").firstOrNull()?.text()?.trim()
        val year = document.select("div.gmr-moviedata").toString().let {
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
            
            // SỬA LỖI: Gọi hàm .toJson() trên đối tượng, thay vì truyền đối tượng vào hàm
            val episodeDataJson = EpisodeData(ajaxUrl, postData).toJson()

            Episode(
                data = episodeDataJson,
                name = serverName
            )
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    // --- Trích xuất liên kết video ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = try {
            parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to parse episode data")
        }

        val ajaxResponse = app.post(episodeData.ajaxUrl, data = episodeData.postData).document
        val iframeSrc = ajaxResponse.select("iframe").firstOrNull()?.attr("src")
            ?: throw ErrorLoadingException("Failed to find iframe source")
            
        return loadExtractor(iframeSrc, subtitleCallback, callback)
    }
}
