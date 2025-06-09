package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    override var name = "MissAV"
    // THAY ĐỔI 1: Cập nhật tên miền
    override var mainUrl = "https://missav.ws" 
    // THAY ĐỔI 2: Cập nhật ngôn ngữ
    override var lang = "vi" 
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // THAY ĐỔI 3: Cập nhật đường dẫn và dịch tiêu đề sang Tiếng Việt
    override val mainPage = mainPageOf(
        "/dm22/vi/new" to "Mới cập nhật",
        "/dm588/vi/release" to "Mới phát hành",
        "/dm621/vi/uncensored-leak" to "Lộ hàng không che",
        "/dm291/vi/today-hot" to "Xem nhiều hôm nay",
        "/dm169/vi/weekly-hot" to "Xem nhiều trong tuần",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href")
        if (href.isBlank() || href == "#") return null

        val title = this.selectFirst("div.my-2 a")?.text()?.trim() ?: return null
        
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
            fixUrl(foundUrl)
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
            // THAY ĐỔI 4: Cập nhật đường dẫn tìm kiếm Tiếng Việt
            val doc = app.get("$mainUrl/vi/search/$query?page=$page").document
            if (doc.select("div.thumbnail").isEmpty()) break
            val results = doc.select("div.thumbnail").mapNotNull { it.toSearchResponse() }
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base, h1.text-lg")?.text()?.trim() ?: "No Title"
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
        val description = document.selectFirst("div.line-clamp-2, div.line-clamp-none")?.text()
        val actors = document.select("a[href*=/actresses/]").map {
            ActorData(Actor(it.text().trim()))
        }
        
        val tags = mutableListOf<String>()
        if (url.contains("-english-subtitle")) {
            tags.add("English Subtitle")
        }
        if (url.contains("-chinese-subtitle")) {
            tags.add("Chinese Subtitle")
        }
        // Có thể thêm logic nhận diện phụ đề Tiếng Việt nếu có
        if (url.contains("-vietnamese-subtitle")) {
            tags.add("Vietnamese Subtitle")
        }


        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.actors = actors
            this.tags = tags
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
                newExtractorLink(
                    url = m3u8Link,
                    name = this.name,
                    source = this.name,
                    type = ExtractorLinkType.M3U8
                ).apply {
                    this.referer = mainUrl
                }.let { link -> callback.invoke(link) }
                
                return true
            }
        }
        return false
    }
}
