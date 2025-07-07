package recloudstream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class SupJav : MainAPI() {
    override var name = "SupJav"
    override var mainUrl = "https://supjav.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/popular/?sort=week" to "Popular This Week",
        "$mainUrl/category/censored-jav" to "Censored JAV",
        "$mainUrl/category/uncensored-jav" to "Uncensored JAV",
        "$mainUrl/category/amateur" to "Amateur",
        "$mainUrl/category/reducing-mosaic" to "Reducing Mosaic",
        "$mainUrl/category/english-subtitles" to "English Subtitles",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                val parts = request.data.split("?")
                "${parts[0]}/page/$page?${parts[1]}"
            } else {
                "${request.data}/page/$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        // Sử dụng let để xử lý trường hợp không tìm thấy element, trả về list rỗng
        val home = document.selectFirst("div.posts")?.let {
            parseVideoList(it)
        } ?: emptyList()

        // Sử dụng newHomePageResponse để tạo response, không còn cảnh báo
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun parseVideoList(element: Element): List<SearchResponse> {
        return element.select("div.post").mapNotNull {
            val titleElement = it.selectFirst("div.con h3 a")
            val title = titleElement?.attr("title") ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val posterUrl = it.selectFirst("a.img img.thumb")?.let { img ->
                img.attr("data-original").ifBlank { img.attr("src") }
            }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return parseVideoList(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.archive-title h1")?.text()?.trim() ?: "No title found"
        val poster = document.selectFirst("div.post-meta img")?.attr("src")
        val plot = "Watch ${title} on SupJav"
        val tags = document.select("div.tags a").map { it.text() }
        
        // Lấy tất cả các link server và gộp chúng lại, phân tách bằng dấu xuống dòng
        val dataLinks = document.select("div.btns a.btn-server")
            .mapNotNull { it.attr("data-link") }
            .joinToString("\n")

        return newMovieLoadResponse(title, url, TvType.NSFW, dataLinks) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = document.select("div.content").find {
                it.selectFirst("h2")?.text()?.contains("You May Also Like") == true
            }?.let { parseVideoList(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Sử dụng coroutineScope để chạy song song các tác vụ và đợi tất cả hoàn thành
        coroutineScope {
            // Tách các link server và duyệt qua từng link
            data.split("\n").forEach { link ->
                if (link.isNotBlank()) {
                    // launch một coroutine mới cho mỗi link, không block luồng chính
                    launch {
                        try {
                            val reversedData = link.reversed()
                            val intermediatePageUrl1 = "https://lk1.supremejav.com/supjav.php?c=$reversedData"
                            val response = app.get(intermediatePageUrl1, referer = "$mainUrl/", allowRedirects = false)

                            val finalPlayerUrl = if (response.code == 302) {
                                response.headers["Location"]
                            } else {
                                response.document.selectFirst("iframe")?.attr("src")
                            }

                            if (finalPlayerUrl != null) {
                                Log.d(name, "Passing to loadExtractor: $finalPlayerUrl")
                                // Gọi loadExtractor cho mỗi link player hợp lệ
                                loadExtractor(finalPlayerUrl, intermediatePageUrl1, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            Log.e(name, "Server failed to load: ${e.message}")
                        }
                    }
                }
            }
        }
        return true
    }
}
