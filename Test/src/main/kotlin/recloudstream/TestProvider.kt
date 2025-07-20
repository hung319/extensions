// Save this file as JavHDzProvider.kt
package recloudstream // Changed package name

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Import the new type
import org.jsoup.nodes.Element
import java.util.Base64

// Define the main provider class
class JavHDzProvider : MainAPI() {
    // Basic information about the provider
    override var mainUrl = "https://javhdz.baby"
    override var name = "JavHDz"
    override val hasMainPage = true
    override var lang = "vi" // Set language to Vietnamese
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW // Mark as NSFW
    )

    // Helper function to parse search results or homepage items
    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.movie-item") ?: return null
        val title = a.attr("title")
        val href = a.attr("href")
        val posterUrl = a.selectFirst("img")?.attr("src")

        // Return null if essential data is missing
        if (title.isBlank() || href.isBlank()) {
            return null
        }
        
        return newMovieSearchResponse(title, fixUrl(href)) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
        }
    }

    // --- Main Page Functions ---

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Construct the URL based on the request data
        val url = if (page == 1) {
            "${mainUrl}/${request.data}"
        } else {
            "${mainUrl}/${request.data}page-$page.html"
        }
        
        val document = app.get(url).document
        
        // Find all movie items on the page
        val home = document.select("ul.last-film-box > li").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    override val mainPage = mainPageOf(
        "" to "Mới cập nhật",
        "video/" to "Video Mới",
        "category/uncensored-3/" to "Không che",
        "category/censored-2/" to "Censored",
        "trending/" to "Trending"
    )

    // --- Search Functionality ---

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        val document = app.get(url).document
        
        return document.select("ul.last-film-box > li").mapNotNull {
            it.toSearchResult()
        }
    }

    // --- Video Loading and Extraction ---

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.header-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
        val description = document.selectFirst("article.block-movie-content p")?.text()?.trim()
        
        // Find recommendations
        val recommendations = document.select("div.movie-list-index ul.last-film-box li").mapNotNull {
            it.toSearchResult()
        }

        // Extract the Base64 encoded video URL from the script tag
        val script = document.selectFirst("script:containsData(jwplayer(\"javhd\").setup)")?.data()
        val base64Url = Regex("file:window\\.atob\\(\"(.+?)\"\\)").find(script ?: "")?.groupValues?.get(1)

        // If Base64 URL is found, decode it and create a streamable link
        val videoUrl = if (base64Url != null) {
            String(Base64.getDecoder().decode(base64Url))
        } else {
            null
        }

        return if (videoUrl != null) {
            newMovieLoadResponse(title, url, TvType.NSFW, videoUrl) {
                this.posterUrl = poster
                this.plot = description
                this.recommendations = recommendations
            }
        } else {
            // Fallback or error if video URL is not found
            null
        }
    }

    // --- Link Loading ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data is the direct video URL from the load function
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                // Updated to use ExtractorLinkType
                type = if (data.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
