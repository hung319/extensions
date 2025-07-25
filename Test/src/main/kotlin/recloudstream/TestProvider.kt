// Dành cho CloudStream 3
// Ngôn ngữ: Kotlin
// Tác giả: CoderAI
// package: recloudstream

package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.google.gson.Gson
import com.lagradost.cloudstream3.utils.ExtractorLinkType

// Định nghĩa plugin
class EpornerPlugin : MainAPI() {
    override var mainUrl = "https://www.eporner.com"
    override var name = "Eporner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Hàm lấy danh sách video trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Xử lý URL cho phân trang
        val url = if (page > 1) "$mainUrl/$page/" else mainUrl
        val document = app.get(url).document

        // Sửa lỗi: Sử dụng selector chung hơn để lấy tất cả video trên trang
        val home = document.select("div.mb").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = "Eporner Videos",
                    list = home
                )
            ),
            hasNext = document.selectFirst(".nmnext") != null
        )
    }

    // Hàm chuyển đổi Element sang SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("p.mbtit a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = this.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
        }
        val quality = this.selectFirst(".mvhdico span")?.text()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            quality?.let { addQuality(it) }
        }
    }

    // Hàm tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        return document.select("div#vidresults div.mb").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm tải thông tin chi tiết (metadata)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val gson = Gson()

        // Trích xuất dữ liệu từ script application/ld+json
        val jsonLdScript = document.selectFirst("script[type=\"application/ld+json\"]")?.data()
        val videoData = try {
            gson.fromJson(jsonLdScript, VideoObject::class.java)
        } catch (e: Exception) {
            null
        }

        val title = videoData?.name ?: document.selectFirst("h1")?.text() ?: return null
        val poster = videoData?.thumbnailUrl?.firstOrNull() ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val description = videoData?.description
        val tags = document.select("#video-info-tags li.vit-category a").map { it.text() }
        val recommendations = document.select("div#relateddiv div.mb").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Hàm tải liên kết video
    override suspend fun loadLinks(
        data: String, // 'data' ở đây là URL từ hàm load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // Sửa lỗi: Cụ thể hóa selector để chỉ lấy link h264 và tránh trùng lặp
        document.select("#downloaddiv .dloaddivcol .download-h264 a").forEach {
            val linkUrl = fixUrl(it.attr("href"))
            val qualityText = it.text()
            val quality = Regex("(\\d+p)").find(qualityText)?.groupValues?.get(1) ?: "Default"
            
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} - $quality",
                    url = linkUrl,
                    referer = data,
                    quality = getQualityFromName(quality),
                    type = ExtractorLinkType.VIDEO
                )
            )
            foundLinks = true
        }
        
        return foundLinks
    }

    // Data class để parse JSON-LD
    private data class VideoObject(
        val name: String?,
        val description: String?,
        val thumbnailUrl: List<String>?,
        val contentUrl: String?
    )
}
