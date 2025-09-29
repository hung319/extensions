package recloudstream

/*
* @CloudstreamProvider: BokepIndoProvider
* @Version: 4.2
* @Author: Coder
* @Language: id
* @TvType: Nsfw
* @Url: https://bokepindoh.monster
* @Info: Provider with hybrid logic and improved error throwing.
*/

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import android.util.Log
import java.net.URI

class BokepIndoProvider : MainAPI() {
    override var name = "BokepIndo"
    override var mainUrl = "https://bokepindoh.monster"
    override var supportedTypes = setOf(TvType.NSFW)
    override var hasMainPage = true
    override var hasDownloadSupport = true

    private val TAG = "BokepIndoProvider"

    override val mainPage = mainPageOf(
        mainUrl to "Mới Nhất",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo",
        "$mainUrl/category/bokep-viral/" to "Bokep Viral",
        "$mainUrl/category/bokep-jav/" to "Bokep JAV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val homePageList = document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(
            HomePageList(request.name, homePageList),
            hasNext = true
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: "Không có tiêu đề"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("div.video-description .desc")?.text()
        val tags = document.select("div.tags-list a[class=label]").map { it.text() }
        val recommendations = document.select("div.under-video-block article.loop-video.thumb-block").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links for: $data")
        val mainDocument = app.get(data).document
        var foundLinks = false

        val multiServerScript = mainDocument.selectFirst("script[id=wpst-main-js-extra]")

        if (multiServerScript != null) {
            Log.d(TAG, "Multi-server page detected.")
            // ... (Logic for multi-server pages remains the same)
        } else {
            Log.d(TAG, "Checking for single-iframe structure.")
            val iframeSrc = mainDocument.selectFirst("div.responsive-player iframe")?.attr("src")
            Log.d(TAG, "Found iframe URL: $iframeSrc")

            if (iframeSrc != null) {
                // ... (Logic for LuluStream pages remains the same)
            }
        }

        // THÊM EXCEPTION NẾU KHÔNG TÌM THẤY BẤT KỲ LINK NÀO
        if (!foundLinks) {
            throw ErrorLoadingException("Không tìm thấy cấu trúc player hợp lệ. Trang có thể đã thay đổi hoặc sử dụng một layout mới.")
        }
        return foundLinks
    }
    
    // Các hàm helper giữ nguyên
    private fun Element.toSearchResponse(): SearchResponse? { /* ... */ }
    private fun getRandomString(length: Int): String { /* ... */ }
}
