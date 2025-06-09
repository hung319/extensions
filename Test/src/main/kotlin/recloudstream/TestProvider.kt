package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element

// Lớp này chứa toàn bộ logic của nhà cung cấp
class MissAVProvider : MainAPI() {
    // Đổi lại domain thành .live theo yêu cầu ban đầu của bạn
    override var mainUrl = "https://missav.live"
    override var name = "MissAV"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // Các trang chính, có thể thêm/bớt tùy ý
    override val mainPage = mainPageOf(
        "/dm514/en/new" to "Recent Update",
        "/dm588/en/release" to "New Releases",
        "/dm621/en/uncensored-leak" to "Uncensored Leak",
        "/dm291/en/today-hot" to "Most Viewed Today",
        "/dm169/en/weekly-hot" to "Most Viewed by Week",
    )

    // Áp dụng đúng định dạng URL trang: ?page=
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document

        val home = document.select("div.thumbnail").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("div.my-2 a")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        // Lặp qua 5 trang đầu tiên để có nhiều kết quả hơn
        for (page in 1..5) {
            val doc = app.get("$mainUrl/en/search/$query?page=$page").document
            if (doc.select("div.thumbnail").isEmpty()) break
            val results = doc.select("div.thumbnail").mapNotNull { it.toSearchResult() }
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
    }

    // `load` chỉ lấy thông tin cơ bản và chuyển url cho `loadLinks` xử lý
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base, h1.text-lg")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.line-clamp-2, div.line-clamp-none")?.text()
        val actors = document.select("a[href*=/actresses/]").map {
            ActorData(Actor(it.text().trim()))
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.actors = actors
        }
    }
    
    // `loadLinks` thực hiện công việc chính là giải mã và lấy link m3u8
    override suspend fun loadLinks(
        data: String, // `data` ở đây là `url` được truyền từ hàm `load`
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val packedJS = document.select("script").firstOrNull { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.data()

        if (packedJS != null) {
            val unpacked = getAndUnpack(packedJS)
            val m3u8Link = unpacked.substringAfter("source='").substringBefore("'")

            if (m3u8Link.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Link,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }
        }
        return false
    }
}
