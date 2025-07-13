package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class AnimetmProvider : MainAPI() {
    override var name = "AnimeTM"
    override var mainUrl = "https://animetm.tv"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    // Sửa lại selector để khớp với cấu trúc HTML mới
    private fun Element.toSearchResult(): SearchResponse {
        val linkElement = this.selectFirst("a")
        val href = fixUrl(linkElement?.attr("href").toString())
        val title = this.selectFirst("div.tray-item-title")?.text() ?: "Không rõ"
        val posterUrl = this.selectFirst("img.tray-item-thumbnail")?.attr("src")
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }
    
    // Hàm chung để lấy dữ liệu từ trang danh sách và trang tìm kiếm
    private suspend fun getPage(url: String): List<SearchResponse> {
        val document = app.get(url).document
        // Selector chính xác cho các mục phim là 'div.tray-item'
        return document.select("div.tray-item").map {
            it.toSearchResult()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Lấy danh sách phim mới từ trang chủ hoặc trang danh sách
        val home = getPage("$mainUrl/danh-sach?page=$page")
        // Trả về một hàng duy nhất có tên "Phim Mới Cập Nhật"
        return newHomePageResponse("Phim Mới Cập Nhật", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getPage("$mainUrl/tim-kiem?keyword=$query")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.text()?.trim() ?: "Không rõ"

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = document.selectFirst("img.film-thumbnail-img")?.attr("src")
            plot = document.selectFirst("div.film-description")?.text()?.trim()

            val episodes = document.select("div.episode-item a").map {
                newEpisode(it.attr("href")) {
                    name = it.attr("title")
                }
            }.reversed()

            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePage = app.get(data).document
        val iframeSrc = episodePage.selectFirst("div#player-wrapper iframe")?.attr("src") 
            ?: return false

        val m3u8Url = "$iframeSrc/master.m3u8"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Server Abyss",
                url = m3u8Url,
                referer = "$mainUrl/",
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.M3U8 
            )
        )
        return true
    }
}
