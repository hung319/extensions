package recloudstream // Giữ package theo yêu cầu của bạn

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class Kurakura21Provider : MainAPI() {
    override var mainUrl = "https://kurakura21.net"
    override var name = "Kurakura21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(this.selectFirst(".gmr-quality-item a")?.text())
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(if (page == 1) mainUrl else "$mainUrl/page/$page").document
        val home = document.select("article.item-infinite, article.thumb-block").mapNotNull {
            it.toSearchResult()
        }
        val hasNext = document.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(list = listOf(HomePageList(name = "Recent Movies", list = home)), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "No Title"
        val poster = document.selectFirst("figure.pull-left img")?.attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val recommendations = document.select("div.gmr-grid article.item").mapNotNull { it.toSearchResult() }
        
        val postId = document.body().attr("class").let {
            Regex("postid-(\\d+)").find(it)?.groupValues?.get(1)
        } ?: throw ErrorLoadingException("Failed to get post ID")

        val servers = document.select("ul.muvipro-player-tabs li a")

        val episodes = servers.mapNotNull { serverElement ->
            val serverName = serverElement.text()
            val tabValue = serverElement.attr("href").removePrefix("#")
            if (tabValue.isBlank()) return@mapNotNull null

            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val postDataString = "action=muvipro_player_content&post_id=$postId&tab=$tabValue"
            
            // Mã hóa dữ liệu vào một chuỗi String duy nhất, phân cách bằng ';;;'
            // Định dạng: "url_ajax;;;chuoi_du_lieu_post"
            val encodedData = "$ajaxUrl;;;$postDataString"

            Episode(data = encodedData, name = serverName)
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Giải mã chuỗi String thủ công
        val parts = data.split(";;;")
        if (parts.size < 2) throw ErrorLoadingException("Invalid episode data")
        
        val ajaxUrl = parts[0]
        val postDataString = parts[1]
        
        // Chuyển chuỗi dữ liệu POST trở lại thành Map
        val postDataMap = postDataString.split("&").associate {
            val (key, value) = it.split("=")
            key to value
        }
        
        try {
            val ajaxResponse = app.post(
                ajaxUrl,
                data = postDataMap,
                referer = mainUrl
            ).document
            
            val iframeSrc = ajaxResponse.selectFirst("iframe")?.attr("src")
                ?: return false
                
            loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
        } catch (e: Exception) {
            // Bỏ qua lỗi nếu có server không hoạt động
        }
            
        return true
    }
}
