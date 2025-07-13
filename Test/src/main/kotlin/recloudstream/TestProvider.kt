// Tên file: JAVtifulProvider.kt
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.google.gson.annotations.SerializedName

class JAVtifulProvider : MainAPI() {
    override var mainUrl = "https://javtiful.com"
    override var name = "JAVtiful"
    override val hasMainPage = true
    override var lang = "ja"
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "/censored" to "Censored Mới Nhất",
        "/uncensored" to "Uncensored Mới Nhất",
        "/trending" to "Thịnh Hành",
        "/videos/sort=most_viewed" to "Xem Nhiều Nhất",
        "/videos/sort=top_rated" to "Xếp Hạng Cao",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            mainUrl + request.data
        } else {
            mainUrl + request.data + "?page=$page"
        }
        val document = app.get(url).document
        
        val home = document.select("div.col.pb-3").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val card = this.selectFirst("div.card") ?: return null
        val link = card.selectFirst("a.video-tmb") ?: return null

        val href = link.attr("href")
        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"

        val title = card.selectFirst("a.video-link")?.attr("title")?.trim() ?: return null
        var posterUrl = card.selectFirst("img.lazy")?.attr("data-src")
        if (posterUrl?.startsWith("/") == true) {
            posterUrl = "$mainUrl$posterUrl"
        }
        
        val qualityString = card.selectFirst("span.label-hd")?.text()?.trim()
        val quality = getQualityFromName(qualityString)

        return newMovieSearchResponse(title, fullHref, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/videos?search_query=$query"
        val document = app.get(url).document
        
        return document.select("div.col.pb-3").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.video-title")?.text()?.trim() ?: return null
        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (posterUrl?.startsWith("/") == true) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val tags = document.select(".video-details__item:contains(từ khóa) a").map { it.text() }
        val recommendations = document.select("#related-actress .splide__slide").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = null
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Định nghĩa một data class để parse JSON trả về từ API
    private data class CdnResponse(
        @SerializedName("playlists") val playlists: String?
    )

    // Hàm lấy link video trực tiếp đã được cập nhật
    override suspend fun loadLinks(
        data: String, // `url` từ `load()`
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tải trang video để lấy thông tin cần thiết
        val document = app.get(data).document

        // 1. Trích xuất video_id từ URL (data)
        val videoId = Regex("""/video/(\d+)/""").find(data)?.groupValues?.get(1) ?: return false

        // 2. Trích xuất token từ thẻ div
        val token = document.selectFirst("#token_full")?.attr("data-csrf-token") ?: return false

        // 3. Chuẩn bị dữ liệu và header để gửi yêu cầu POST
        val postUrl = "$mainUrl/ajax/get_cdn"
        val postData = mapOf(
            "video_id" to videoId,
            "pid_c" to "",
            "token" to token
        )
        val headers = mapOf("Referer" to data)

        // 4. Gửi yêu cầu POST và nhận kết quả
        val ajaxResponse = app.post(postUrl, headers = headers, data = postData).text

        // 5. Parse JSON để lấy link video
        val cdnResponse = parseJson<CdnResponse>(ajaxResponse)
        val videoUrl = cdnResponse.playlists ?: return false

        // 6. Trả về link video cho player
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Javtiful CDN",
                url = videoUrl,
                referer = mainUrl, // Luôn đặt referer để đảm bảo link hoạt động
                quality = Qualities.Unknown.value,
                // Dùng ExtractorLinkType.VIDEO cho link MP4/m3u8...
                type = ExtractorLinkType.VIDEO 
            )
        )
        
        return true
    }
}
