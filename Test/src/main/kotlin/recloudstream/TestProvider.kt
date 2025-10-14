package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FanxxxProvider : MainAPI() {
    override var mainUrl = "https://fanxxx.org"
    override var name = "Fanxxx"
    override val hasMainPage = true
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.NSFW)

    // Các hàm getMainPage, search, load, toSearchResult giữ nguyên như cũ.
    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("span.title")?.text()?.trim() ?: "Unknown Title"
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("data-src")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val homePageList = document.select("article.thumb-block").map { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList("Newest Videos", homePageList),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.thumb-block").map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name]")?.text() ?: "No title"
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
        val iframeUrl = document.selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("No video iframe found on page")

        return newMovieLoadResponse(title, url, TvType.NSFW, iframeUrl) {
            this.posterUrl = poster
        }
    }

    /**
     * Coder's Note:
     * Đây là cách làm đúng chuẩn. Provider chỉ cần lấy URL player cuối cùng
     * và giao phó toàn bộ việc xử lý cho `loadExtractor`.
     */
    override suspend fun loadLinks(
        data: String, // Đây là iframeUrl từ hàm load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Theo dõi chuyển hướng (nếu có) để lấy URL player cuối cùng.
        val playerPageUrl = app.get(data, referer = mainUrl).url

        // Gọi `loadExtractor`, CloudStream sẽ tự tìm extractor phù hợp và chạy nó.
        return loadExtractor(playerPageUrl, mainUrl, subtitleCallback, callback)
    }
}
