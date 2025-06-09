package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    override var name = "MissAV"
    override var mainUrl = "https://missav.live"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "/dm22/en/new" to "Latest",
        "/dm588/en/release" to "New Releases",
        "/dm621/en/uncensored-leak" to "Uncensored Leak",
        "/dm291/en/today-hot" to "Most Viewed Today",
        "/dm169/en/weekly-hot" to "Most Viewed by Week",
    )

    // SỬA LỖI: Đảm bảo hàm này nằm bên trong lớp và sử dụng fixUrl()
    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href")
        if (href.isBlank() || href == "#") return null

        val title = this.selectFirst("div.my-2 a")?.text()?.trim() ?: return null
        
        // Cập nhật logic lấy poster, thay `makeAbsoluteUrl` bằng `fixUrl`
        val posterUrl = this.selectFirst("img")?.let { img ->
            val potentialAttrs = listOf("data-src", "src", "data-original")
            var foundUrl = ""
            for (attr in potentialAttrs) {
                val url = img.attr(attr)
                if (url.isNotBlank()) {
                    foundUrl = url
                    break
                }
            }
            // fixUrl là cách mới và tốt hơn để xử lý link tương đối/tuyệt đối
            foundUrl.fixUrl(mainUrl)
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document
        
        val items = document.select("div.thumbnail").mapNotNull {
            it.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (page in 1..5) {
            val doc = app.get("$mainUrl/en/search/$query?page=$page").document
            if (doc.select("div.thumbnail").isEmpty()) break
            val results = doc.select("div.thumbnail").mapNotNull { it.toSearchResponse() }
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
    }

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
    
    override suspend fun loadLinks(
        data: String,
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
                // SỬA LỖI: Thay thế tham số `referer` không hợp lệ
                newExtractorLink(
                    url = m3u8Link,
                    name = this.name,
                    source = this.name,
                    type = ExtractorLinkType.M3U8
                ).apply {
                    this.referer = mainUrl // Đặt referer trong khối .apply{}
                }.let { link -> callback.invoke(link) }
                
                return true
            }
        }
        return false
    }
}
