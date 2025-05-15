package com.cloudstream.txnhhprovider // Giữ nguyên package hoặc thay đổi nếu bạn cần

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.json.JSONArray
import java.net.URLEncoder // Thêm import này
import java.nio.charset.StandardCharsets // Thêm import này


class TXNHHProvider : MainAPI() {
    override var mainUrl = "https://www.txnhh.com"
    override var name = "TXNHH"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        fun fixUrl(url: String?, domain: String): String? {
            if (url.isNullOrEmpty()) return null
            return if (url.startsWith("http")) {
                url
            } else {
                domain + (if (url.startsWith("/")) url else "/$url")
            }
        }

        // URI ಗುರುತಿಸುವಿಕೆಗಳು (URI identifiers)
        const val URI_PREFIX_HOMEPAGE_CATEGORIES = "txnhhprovider://homepagecategories"
        const val URI_PREFIX_CATEGORY_PAGE = "txnhhprovider://categorypage/" // Lưu ý dấu / ở cuối
    }

    override val mainPage = mainPageOf(
        URI_PREFIX_HOMEPAGE_CATEGORIES to "Danh Mục Từ Trang Chủ"
        // Thêm các mục tĩnh khác ở đây nếu cần, ví dụ:
        // "$mainUrl/new-videos" to "Video Mới Nhất", // Sẽ được loadPage xử lý trực tiếp
    )

    // getMainPage không cần override phức tạp nếu mainPage (val) đã đủ
    // override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResult? { ... }


    // Helper class nội bộ để chứa dữ liệu trang đã parse
    internal data class VideoListPage(
        val items: List<SearchResponse>,
        val nextPageUrl: String? = null
    )

    private fun parseVideoFromBlock(element: org.jsoup.nodes.Element): SearchResponse? {
        val linkTag = element.selectFirst("div.thumb > a") ?: return null
        val videoPageUrl = fixUrl(linkTag.attr("href"), mainUrl) ?: return null

        val imgTag = linkTag.selectFirst("img")
        val posterUrl = imgTag?.attr("data-src") ?: imgTag?.attr("src")

        val titleTag = element.selectFirst("div.thumb-under > p > a")
        val title = titleTag?.attr("title")?.trim() ?: titleTag?.text()?.trim() ?: "N/A"

        val metadataText = element.selectFirst("div.thumb-under > p.metadata")?.text() ?: ""
        val durationRegex = Regex("""(\d+)min""")
        val durationMinutes = durationRegex.find(metadataText)?.groupValues?.get(1)?.toIntOrNull()

        val qualityText = element.selectFirst("div.thumb-under > p.metadata > span.video-hd")?.text()
            ?.replace("-", "")?.trim()

        return newMovieSearchResponse(title, videoPageUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.duration = durationMinutes // CloudStream kỳ vọng duration là Int? (số giây), không phải phút
            if (!qualityText.isNullOrBlank()) {
                this.quality = getQualityFromString(qualityText)
            }
        }
    }

    private suspend fun parseAndExtractHomepageCategories(doc: Document): List<SearchResponse> {
        val categories = mutableListOf<SearchResponse>()
        val scriptTag = doc.select("script:containsData(xv.cats.write_thumb_block_list)").html()
        val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\((.+?),\s*".*?"\s*\);""")
        val matchResult = regex.find(scriptTag)
        val jsonArrayString = matchResult?.groups?.get(1)?.value?.trim()

        if (jsonArrayString != null) {
            try {
                val jsonArray = JSONArray(jsonArrayString)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val title = item.optString("t")
                    val poster = fixUrl(item.optString("i"), mainUrl)
                    val actualCategoryUrl = fixUrl(item.optString("u"), mainUrl)

                    if (title.isNotBlank() && !actualCategoryUrl.isNullOrBlank()) {
                        // Encode URL để đảm bảo nó an toàn khi dùng làm một phần của URI khác
                        val encodedActualUrl = URLEncoder.encode(actualCategoryUrl, StandardCharsets.UTF_8.toString())
                        val providerUriForCategory = "$URI_PREFIX_CATEGORY_PAGE$encodedActualUrl"

                        categories.add(
                            newAnimeSearchResponse( // Sử dụng newAnimeSearchResponse cho các collection/category
                                name = title,
                                url = providerUriForCategory, // URL để loadPage biết cần tải category này
                                type = TvType.Others // Đánh dấu là một collection
                            ) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                logError(e) // Ghi log lỗi
            }
        }
        return categories
    }


    private suspend fun parseRegularVideoListPage(doc: Document, currentUrl: String): VideoListPage {
        val items = doc.select("div.mozaique div.thumb-block")
            .mapNotNull { parseVideoFromBlock(it) }

        var nextPageUrl: String? = null
        val pagination = doc.selectFirst("div.pagination ul")
        if (pagination != null) {
            val nextButton = pagination.selectFirst("li a.next")
            if (nextButton != null) {
                val href = nextButton.attr("href")
                if (href.isNotBlank() && href != "#") {
                    nextPageUrl = fixUrl(href, mainUrl)
                }
            } else {
                 // Logic tìm trang kế tiếp dựa trên số trang hiện tại (nếu có)
                val activePageLink = pagination.selectFirst("li a.active")
                val currentPageNum = activePageLink?.text()?.toIntOrNull()
                if (currentPageNum != null) {
                    val nextPageNumCandidateLink = activePageLink?.parent()?.nextElementSibling()?.selectFirst("a")
                    if (nextPageNumCandidateLink != null) {
                         val nextPageHref = nextPageNumCandidateLink.attr("href")
                         if(nextPageHref.isNotBlank() && nextPageHref != "#") {
                            nextPageUrl = fixUrl(nextPageHref, mainUrl)
                         }
                    }
                }
            }
        }
         // Xử lý trường hợp trang đầu tiên của tìm kiếm hoặc category không có số trang rõ ràng
        if (nextPageUrl == null) {
            if (currentUrl.contains("?k=") && !currentUrl.contains("&p=")) { // Tìm kiếm, trang đầu (p=0)
                nextPageUrl = "$currentUrl&p=1" // Trang tiếp theo p=1
            } else if ((currentUrl.contains("/search/") || currentUrl.contains("/todays-selection")) && !currentUrl.matches(Regex(".*/\\d+$"))) {
                // Category/selection trang đầu, trang tiếp theo là /1
                nextPageUrl = if (currentUrl.endsWith("/")) "${currentUrl}1" else "$currentUrl/1"
            }
        }

        return VideoListPage(items, nextPageUrl)
    }

    override suspend fun loadPage(url: String): LoadPageResult? {
        log("loadPage called with URL: $url")
        return try {
            when {
                url.startsWith(URI_PREFIX_HOMEPAGE_CATEGORIES) -> {
                    val doc = app.get(mainUrl).document
                    val categoryCards = parseAndExtractHomepageCategories(doc)
                    newPage(categoryCards, null) // Không có trang tiếp theo cho danh sách category từ trang chủ
                }
                url.startsWith(URI_PREFIX_CATEGORY_PAGE) -> {
                    val encodedActualUrl = url.removePrefix(URI_PREFIX_CATEGORY_PAGE)
                    val actualUrl = java.net.URLDecoder.decode(encodedActualUrl, StandardCharsets.UTF_8.toString())
                    val doc = app.get(actualUrl).document
                    val pageData = parseRegularVideoListPage(doc, actualUrl)
                    newPage(pageData.items, pageData.nextPageUrl)
                }
                // Xử lý các URL next page thông thường (đã được fixUrl)
                url.startsWith(mainUrl) -> {
                    val doc = app.get(url).document
                    val pageData = parseRegularVideoListPage(doc, url)
                    newPage(pageData.items, pageData.nextPageUrl)
                }
                else -> null
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // URL tìm kiếm trên txnhh.com dùng ?k=keyword&p=pagenum (0-indexed)
        val searchStartUrl = "$mainUrl/?k=${URLEncoder.encode(query, StandardCharsets.UTF_8.toString())}&p=0"
        // Để CloudStream xử lý phân trang, loadPage cần có khả năng xử lý URL tìm kiếm
        // Tạo một URL nội bộ để loadPage xử lý
        val providerUriForSearch = "$URI_PREFIX_CATEGORY_PAGE${URLEncoder.encode(searchStartUrl, StandardCharsets.UTF_8.toString())}"

        // Trả về một item "collection" giả để CloudStream gọi loadPage
        // CloudStream sẽ hiển thị tên này, và khi click sẽ gọi loadPage với providerUriForSearch
        return listOf(
            newAnimeSearchResponse( // Hoặc một loại card phù hợp khác cho "kết quả tìm kiếm"
                name = "Kết quả cho: $query",
                url = providerUriForSearch,
                type = TvType.Others
            ) {
                // Không cần poster cho mục này
            }
        )
    }

    override suspend fun loadLinks(
        data: String, // URL trang video
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Hoàn thiện phần này
        log("loadLinks called for: $data. Implementation pending.")
        // Ví dụ:
        // try {
        //     val document = app.get(data).document
        //     val scriptContent = document.select("script:containsData(html5player.setVideoUrlHigh)").html()
        //     val videoUrlHigh = Regex("""html5player\.setVideoUrlHigh\s*\(['"]([^'"]+)['"]\)""").find(scriptContent)?.groupValues?.get(1)
        //     // Tương tự cho setVideoUrlLow, setVideoHLS, etc.
        //
        //     if (!videoUrlHigh.isNullOrBlank()) {
        //         callback(
        //             ExtractorLink(
        //                 source = this.name,
        //                 name = "Chất lượng Cao", // Hoặc lấy từ tên biến JS nếu có
        //                 url = videoUrlHigh,
        //                 referer = data,
        //                 quality = Qualities.P720.value // Hoặc xác định chất lượng
        //             )
        //         )
        //         return true
        //     }
        // } catch (e: Exception) {
        //     logError(e)
        // }
        return false
    }

    private fun getQualityFromString(qualityString: String?): InferredQuality? {
        return qualityString?.filter { it.isDigit() }?.toIntOrNull()?.let {
            InferredQuality.Custom(it) // Gán giá trị Int cho Custom
        }
    }
}
