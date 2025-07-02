// Chỉ đổi package ở đây theo yêu cầu
package recloudstream

// Giữ nguyên các import từ com.lagradost.cloudstream3
import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

@CloudStreamPlugin
class HentaiSexBlogProvider : MainAPI() {
    // --- Metadata cho plugin ---
    override var name = "HentaiSex Blog"
    override var mainUrl = "https://hentaisex.blog"
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)
    override var hasMainPage = true

    // --- Hàm tải trang chính ---
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document

        val home = document.select("div.flw-item").mapNotNull {
            it.toSearchResult()
        }

        return HomePageResponse(arrayListOf(HomePageList("Mới cập nhật", home)), hasNext = home.isNotEmpty())
    }

    // --- Hàm phụ trợ chuyển đổi HTML ---
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("h3.film-name a") ?: return null
        val href = linkElement.attr("href")
        val title = linkElement.attr("title")

        val posterUrl = this.selectFirst("img.film-poster-img")?.attr("data-original")?.let {
            if (it.startsWith("/")) mainUrl + it else it
        }

        if (title.isBlank() || href.isBlank()) return null

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // --- Hàm tìm kiếm ---
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?search=$query").document
        return document.select("div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // --- Hàm tải chi tiết phim ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.video-title")?.text() ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.about.mb-3")?.text()
        val tags = document.select("div.genres.mb-4 a").map { it.text() }

        val episodes = arrayListOf(
            Episode(
                data = url,
                name = "Xem Phim"
            )
        )

        return newTvShowLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
        }
    }

    // --- Hàm lấy link video trực tiếp ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("li.pu-link").forEach { serverElement ->
            val videoUrl = serverElement.attr("data-link")
            val serverName = serverElement.selectFirst("span.sv-name")?.text() ?: "Server"

            if (videoUrl.isNotBlank()) {
                 callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = serverName,
                        url = videoUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }
        return true
    }
}
