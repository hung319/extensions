// Desription: This is a plugin for the website fullxcinema.com
// Author: Coder
// Date: 2025-07-26

package recloudstream // <-- Đã đổi package name

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

// <-- Đã xóa class FullxcinemaProvider

class Fullxcinema : MainAPI() {
    override var mainUrl = "https://fullxcinema.com"
    override var name = "Fullxcinema"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.NSFW
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("header.entry-header span")?.text() ?: return null
        // Sửa lỗi lazy loading ảnh, sử dụng data-src
        val posterUrl = this.selectFirst("div.post-thumbnail-container img")?.attr("data-src")

        return MovieSearchResponse(
            title,
            href,
            this@Fullxcinema.name,
            TvType.Movie,
            posterUrl,
            null,
            null
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = "Latest Movies",
                list = home,
                isHorizontal = true
            ),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Could not load title")
        val poster = document.selectFirst("""meta[property="og:image"]""")?.attr("content")
        val description = document.selectFirst("div.video-description div.desc.more")?.text()?.trim()
        val tags = document.select("div.tags-list a[rel='tag']").map { it.text() }
        val iframeUrl = document.selectFirst("div.responsive-player iframe")?.attr("src")
            ?: throw ErrorLoadingException("Could not find video iframe")

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = iframeUrl
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframeDocument = app.get(data).document
        val videoUrl = iframeDocument.selectFirst("video > source")?.attr("src")
            ?: throw ErrorLoadingException("Could not extract video source from iframe")

        callback(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                referer = "$mainUrl/",
                quality = getQualityFromName(""), // Auto quality
                // Cập nhật isM3u8 sang ExtractorLinkType
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            )
        )

        return true
    }
}
