// Bấm vào "Copy" ở góc trên bên phải để sao chép mã
package recloudstream // Đã thêm package theo yêu cầu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class XpornTvProvider : MainAPI() {
    override var mainUrl = "https://www.xporn.tv"
    override var name = "Xporn.tv"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest Videos",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/top-rated/" to "Top Rated",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("strong.title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")
        val duration = this.selectFirst("div.wrap > div:last-child")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.otherVimeStuff = listOf(duration)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/search/$query/").document
        return searchResponse.select("div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Video"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val tags = document.select("div.item:contains(Tags) a").map { it.text() }
        
        // Tìm đoạn script chứa flashvars
        val script = document.select("script").find { it.data().contains("flashvars") }?.data()

        if (script != null) {
            // Sử dụng regex để trích xuất video_url
            val videoUrlRegex = Regex("""'video_url'\s*:\s*'function/0/(.+?\.mp4/)'""")
            val videoUrlMatch = videoUrlRegex.find(script)
            val finalVideoUrl = videoUrlMatch?.groups?.get(1)?.value

            if (finalVideoUrl != null) {
                return newMovieLoadResponse(title, url, TvType.NSFW, finalVideoUrl) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }
        }
        
        throw ErrorLoadingException("Could not find video link")
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data ở đây chính là URL của video đã được trả về từ load()
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                // Đã cập nhật type, vì link là file .mp4 trực tiếp
                type = ExtractorLinkType.VIDEO 
            )
        )
        return true
    }
}
