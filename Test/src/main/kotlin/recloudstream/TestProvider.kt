// Desription: провайдер для сайта VeoHentai
// Date: 2025-08-05
// Version: 1.9
// Author: Coder

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class VeoHentaiProvider : MainAPI() {
    override var mainUrl = "https://veohentai.com"
    override var name = "VeoHentai"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // ============================ HOMEPAGE & SEARCH ============================
    override val mainPage = mainPageOf(
        "/" to "Episodios Recientes",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page/").document
        val home = document.select("div#posts-home a").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        return searchResponse.select("div.grid a").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = this.attr("href")
        if (href.isEmpty()) return null

        val title = this.selectFirst("h2")?.text() ?: return null
        val posterUrl = this.selectFirst("figure img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
        }
    }

    // ======================= LOAD EPISODE/MOVIE INFO =======================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Ver ","")?.replace(" - Ver Hentai en Español","")?.trim()
            ?: throw Error("Could not find title")
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("meta[property=article:tag]").map { it.attr("content") }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // ======================= LOAD VIDEO LINKS (LOGIC MỚI VỚI SLUG) =======================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // `data` chính là URL của trang xem phim, ví dụ: "https://veohentai.com/ver/shoujo-ramune-episodio-5/"
        
        // 1. Trích xuất slug từ URL
        val slug = data.trimEnd('/').substringAfterLast("/ver/")

        if (slug.isBlank()) {
            throw ErrorLoadingException("Không thể trích xuất slug từ URL: $data")
        }

        // 2. Chuyển đổi slug theo quy tắc: thay thế "-episodio-" bằng "-"
        val modifiedSlug = slug.replace("-episodio-", "-")
        
        // 3. Xây dựng URL video .mp4 cuối cùng
        val videoUrl = "https://r2.1hanime.com/$modifiedSlug.mp4"

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "${this.name} MP4", // Tên nguồn để dễ nhận biết
                url = videoUrl,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO // Đây là link video trực tiếp
            )
        )

        return true
    }
}
