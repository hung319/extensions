package com.recloudstream.extractors.pornhub

// Thêm các import cần thiết
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson // <- THÊM DÒNG NÀY
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class PornhubProvider : MainAPI() {
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com"
    override var lang = "en"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    private val headers get() = mapOf("User-Agent" to userAgent)

    // ... (Các hàm getMainPage, search, load giữ nguyên như cũ) ...

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/", headers = headers).document
        
        val home = document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.absUrl("href") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img -> 
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
        
        return newHomePageResponse("Recommended", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?search=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("li.pcVideoListItem").mapNotNull {
            val titleElement = it.selectFirst("span.title a")
            val title = titleElement?.attr("title") ?: ""
            val href = titleElement?.absUrl("href") ?: return@mapNotNull null
            val image = it.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            }

            newMovieSearchResponse(name = title, url = href, type = TvType.NSFW) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.title span.inlineFree")?.text() 
            ?: document.selectFirst("title")?.text() 
            ?: ""

        val pageText = document.html()
        val poster = Regex("""image_url":"([^"]+)""").find(pageText)?.groupValues?.get(1)
        
        val plot = document.selectFirst("div.video-description-text")?.text()

        return newMovieLoadResponse(name = title, url = url, type = TvType.NSFW, dataUrl = url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private data class MediaDefinition(
        @JsonProperty("videoUrl") val videoUrl: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("format") val format: String?
    )
    private data class FlashVars(
        @JsonProperty("mediaDefinitions") val mediaDefinitions: List<MediaDefinition>?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        val scriptText = document.select("script").find { it.data().contains("var flashvars_") }?.data() 
            ?: return false

        val jsonString = Regex("""var flashvars_\d+ = (\{.*\});""").find(scriptText)?.groupValues?.get(1) 
            ?: return false
        
        // Sửa lại cách gọi hàm parseJson
        val flashVars = parseJson<FlashVars>(jsonString)

        flashVars.mediaDefinitions?.forEach { media ->
            val videoUrl = media.videoUrl
            val qualityStr = media.quality

            if (videoUrl.isNullOrBlank() || qualityStr.isNullOrBlank()) {
                // Do nothing, just continue to the next item in the loop
            } else {
                val qualityInt = qualityStr.toIntOrNull() ?: 0
                
                when (media.format) {
                    "hls" -> {
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "${qualityStr}p (HLS)",
                                url = videoUrl,
                                referer = data,
                                quality = qualityInt,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                    "mp4" -> {
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "${qualityStr}p (MP4)",
                                url = videoUrl,
                                referer = data,
                                quality = qualityInt,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            }
        }
        
        return true
    }
}
