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

    // *** ĐÃ CẬP NHẬT LOGIC ĐỂ XỬ LÝ NHIỀU CẤU TRÚC HTML ***
    private fun Element.toSearchResult(): SearchResponse? {
        // Thử cấu trúc 1: div.film-item
        val filmLink = this.selectFirst("a.film-item-link")
        if (filmLink != null) {
            val href = fixUrl(filmLink.attr("href"))
            val title = filmLink.attr("title")
            val posterUrl = this.selectFirst("img.film-item-img")?.attr("src")
            return newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }

        // Thử cấu trúc 2: div.tray-item
        val trayLink = this.selectFirst("a")
        if (trayLink != null) {
            val href = fixUrl(trayLink.attr("href"))
            val title = this.selectFirst(".tray-item-title")?.text() ?: return null
            val posterUrl = this.selectFirst("img.tray-item-thumbnail")?.attr("src")
            return newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
        
        return null
    }

    private suspend fun getPage(url: String): List<SearchResponse> {
        val document = app.get(url).document
        // *** SỬ DỤNG BỘ CHỌN KẾT HỢP ĐỂ TÌM TẤT CẢ CÁC MỤC PHIM ***
        return document.select("div.film-item, div.tray-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = getPage("$mainUrl/moi-cap-nhat?page=$page")
        // Chỉ trả về nếu tìm thấy kết quả để tránh lỗi "does not have any items"
        if (home.isNotEmpty()) {
            return newHomePageResponse("Phim Mới Cập Nhật", home)
        }
        return newHomePageResponse("Trang chủ", emptyList())
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
