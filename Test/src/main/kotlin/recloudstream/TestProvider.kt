package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.MultipartBody

class JAVtifulProvider : MainAPI() {
    override var mainUrl = "https://javtiful.com"
    override var name = "JAVtiful"
    override val hasMainPage = true
    override var lang = "vi"

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
        val url = if (page > 1) {
            "${mainUrl}${request.data}?page=$page"
        } else {
            "${mainUrl}${request.data}"
        }
        val document = app.get(url).document

        val home = document.select("div.col.pb-3").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun getSearchQuality(qualityString: String?): SearchQuality? {
        return when (qualityString?.uppercase()) {
            "HD", "FHD" -> SearchQuality.HD
            else -> null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val card = this.selectFirst("div.card") ?: return null

        if (card.selectFirst("span.badge:contains(SPONSOR)") != null) {
            return null
        }

        val link = card.selectFirst("a.video-tmb") ?: return null
        val href = link.attr("href")

        val fullHref = if (href.startsWith("http")) href else mainUrl + href

        val title = card.selectFirst("a.video-link")?.attr("title")?.trim() ?: return null
        var posterUrl = card.selectFirst("img.lazy")?.attr("data-src")
        if (posterUrl?.startsWith("/") == true) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val qualityString = card.selectFirst("span.label-hd")?.text()?.trim()

        return newMovieSearchResponse(title, fullHref, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.quality = getSearchQuality(qualityString)
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

        val videoId = Regex("""/video/(\d+)/""").find(url)?.groupValues?.get(1)
        val posterUrl = videoId?.let { "$mainUrl/media/videos/tmb1/$it/1.jpg" }

        val tags = document.select(".video-details__item:contains(từ khóa) a").map { it.text() }
        val recommendations = document.select("#related-actress .splide__slide, .related-videos .col").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = null
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private data class CdnResponse(
        @SerializedName("playlists") val playlists: String?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Lấy HTML của trang video để lấy token, request này cũng giúp thiết lập cookie cần thiết.
        val document = app.get(data).document

        val videoId = Regex("""/video/(\d+)/""").find(data)?.groupValues?.get(1) ?: return false
        val token = document.selectFirst("#token_full")?.attr("data-csrf-token") ?: return false

        val postUrl = "$mainUrl/ajax/get_cdn"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("video_id", videoId)
            .addFormDataPart("pid_c", "")
            .addFormDataPart("token", token)
            .build()
            
        // == FIX ==
        // Cung cấp bộ headers đầy đủ để vượt qua kiểm tra của server
        val headers = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "vi-VN,vi;q=0.9",
            "Origin" to mainUrl,
            "Referer" to data,
            "Sec-Ch-Ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "Sec-Ch-Ua-Mobile" to "?1",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest"
        )
        
        val ajaxResponse = app.post(postUrl, headers = headers, requestBody = requestBody).text
        val cdnResponse = parseJson<CdnResponse>(ajaxResponse)
        val videoUrl = cdnResponse.playlists ?: return false

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Javtiful S3 CDN",
                url = videoUrl,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        )

        return true
    }
}
