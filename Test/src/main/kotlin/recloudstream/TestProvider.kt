package com.cloudstream.txnhhprovider // <-- THAY ĐỔI TÊN PACKAGE NÀY NẾU CẦN

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.json.JSONArray

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
    }

    // Sử dụng internal URIs để loadPage xử lý các mục từ trang chủ
    override val mainPage = mainPageOf(
        "txnhhprovider://homepage" to "Danh Mục Trang Chủ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResult? {
        // Vì loadPage sẽ xử lý URL "txnhhprovider://homepage", chúng ta không cần fetch ở đây nữa.
        // Chỉ cần trả về cấu trúc HomePageResult.
        // Nếu bạn muốn có nhiều section tĩnh từ mainPage, bạn có thể định nghĩa chúng ở đây.
        // Ví dụ này chỉ có một section sẽ được load bởi loadPage.
        if (request.data == "txnhhprovider://homepage") {
             // HomePageList sẽ được điền bởi loadPage khi người dùng click vào mục này
            return HomePageResult(listOf(HomePageList(request.name, emptyList(), isHorizontalImages = true)))
        }
        return null
    }

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

        val quality = element.selectFirst("div.thumb-under > p.metadata > span.video-hd")?.text()
            ?.replace("-", "")?.trim()

        return newMovieSearchResponse(title, videoPageUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.duration = durationMinutes
            if (!quality.isNullOrBlank()) {
                this.quality = getQualityFromString(quality)
            }
        }
    }

    private suspend fun parseVideoListPage(url: String, isHomePageCategories: Boolean = false): Page {
        val doc = app.get(url).document
        var items = emptyList<SearchResponse>()

        if(isHomePageCategories) {
            // Trích xuất JSON từ script cho trang chủ
            val scriptTag = doc.select("script:containsData(xv.cats.write_thumb_block_list)").html()
            val regex = Regex("""xv\.cats\.write_thumb_block_list\s*\((.+?),\s*".*?"\s*\);""")
            val matchResult = regex.find(scriptTag)
            val jsonArrayString = matchResult?.groups?.get(1)?.value?.trim()

            if (jsonArrayString != null) {
                try {
                    val jsonArray = JSONArray(jsonArrayString)
                    val categories = mutableListOf<SearchResponse>()
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val title = item.optString("t")
                        val posterUrl = fixUrl(item.optString("i"), mainUrl)
                        var categoryUrl = fixUrl(item.optString("u"), mainUrl)

                        if (title.isNotBlank() && !categoryUrl.isNullOrBlank()) {
                            val pageActionUrl = "txnhhprovider://loadpage/$categoryUrl"
                            categories.add(
                                newAnimeSearchResponse( // Sử dụng newAnimeSearchResponse hoặc tương tự cho collections
                                    name = title,
                                    url = pageActionUrl,
                                    type = TvType.Others // Để CloudStream biết đây là collection
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                    items = categories
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            items = doc.select("div.mozaique div.thumb-block")
                .mapNotNull { parseVideoFromBlock(it) }
        }


        // Xử lý pagination (chỉ áp dụng cho trang danh sách video, không phải trang chủ categories)
        var nextPageUrl: String? = null
        if (!isHomePageCategories) {
            val pagination = doc.selectFirst("div.pagination ul")
            if (pagination != null) {
                val nextButton = pagination.selectFirst("li a.next")
                if (nextButton != null) {
                    val href = nextButton.attr("href")
                    if (href.isNotBlank() && href != "#") { // Đảm bảo href có giá trị và không phải là "#"
                         nextPageUrl = fixUrl(href, mainUrl)
                    }
                } else {
                    val activePageLink = pagination.selectFirst("li a.active")
                    if (activePageLink != null) {
                        val currentPageNumStr = activePageLink.text()
                        val currentPageNum = currentPageNumStr.toIntOrNull()
                        if (currentPageNum != null) {
                            val nextPageNum = currentPageNum + 1
                            // Cố gắng tìm link cho trang nextPageNum.
                            // Selector này có thể cần điều chỉnh tùy thuộc vào cấu trúc HTML chính xác của trang.
                            // Ví dụ: nếu URL là /search/category/PAGENUM
                            // hoặc /?k=query&p=PAGENUM
                            val currentPath = java.net.URL(url).path
                            val queryParams = java.net.URL(url).query

                            if (currentPath.matches(Regex(".*/\\d+"))) { // /search/category/PAGENUM
                                nextPageUrl = currentPath.replace(Regex("\\d+$"), nextPageNum.toString())
                                if (!queryParams.isNullOrBlank()) nextPageUrl += "?$queryParams"
                                nextPageUrl = fixUrl(nextPageUrl, mainUrl)
                            } else if (url.contains("?k=") && queryParams != null) { // /?k=query&p=PAGENUM
                                if (queryParams.contains("p=")) {
                                    nextPageUrl = url.replace(Regex("p=\\d+"), "p=${(activePageLink.parent()?.nextElementSibling()?.selectFirst("a")?.text()?.toIntOrNull() ?: nextPageNum) -1}") // site dùng 0-indexed
                                } else {
                                    nextPageUrl = "$url&p=1" // Trang đầu tiên p=0, trang 2 là p=1
                                }
                            } else if (currentPath.endsWith(url.substringAfterLast('/')) && !currentPath.contains(Regex("\\d+"))) { // Dạng /category (trang đầu)
                                 nextPageUrl = fixUrl("$currentPath/$nextPageNum", mainUrl)
                            }
                        }
                    }
                }
            }
             // Nếu nextPageUrl vẫn null và URL hiện tại không có param 'p' (cho tìm kiếm) hoặc số ở cuối (cho category)
            if (nextPageUrl == null) {
                if (url.contains("?k=") && !url.contains("&p=")) {
                    nextPageUrl = "$url&p=1" // Trang 2 của tìm kiếm là p=1 (vì p=0 là trang 1)
                } else if ((url.contains("/search/") || url.contains("/todays-selection")) && !url.matches(Regex(".*/\\d+$"))) {
                    // Trang đầu của category/selection (vd: /search/asian), trang tiếp theo là /1
                    nextPageUrl = if (url.endsWith("/")) "${url}1" else "$url/1"
                }
            }
        }
        return Page(url, items, nextPageUrl)
    }


    override suspend fun loadPage(url: String): LoadPageResult? {
        val actualUrlToLoad = when {
            url.startsWith("txnhhprovider://homepage") -> mainUrl // URL thực sự cho trang chủ categories
            url.startsWith("txnhhprovider://loadpage/") -> url.removePrefix("txnhhprovider://loadpage/")
            else -> url // Các trường hợp khác (next page urls)
        }

        val isHomePageRequest = url.startsWith("txnhhprovider://homepage")
        val pageData = parseVideoListPage(actualUrlToLoad, isHomePageRequest)

        // Nếu là request từ trang chủ để load categories, chúng ta không muốn "Next page"
        val finalNextPageUrl = if (isHomePageRequest) null else pageData.nextPageUrl

        return newPage(pageData.items, finalNextPageUrl)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?k=${query}&p=0"
        // loadPage sẽ được CloudStream gọi để xử lý phân trang cho search nếu provider có loadPage
        // Vì vậy, search chỉ cần trả về kết quả của trang đầu tiên.
        // Tuy nhiên, để CloudStream biết có trang tiếp theo, ta cần loadPage có thể xử lý URL tìm kiếm.
        // Cách đơn giản nhất là search cũng trả về NewPage với nextpage key là URL trang tìm kiếm tiếp theo.
        // Nhưng API search của MainAPI là List<SearchResponse>.
        // Chúng ta sẽ dựa vào CloudStream tự động gọi `loadPage` với URL đã được cấu trúc đúng từ `newPage` (nếu search trả về Page)
        // hoặc nó sẽ gọi lại `search` với page number tăng lên nếu không có `loadPage`.
        // Trong trường hợp này, vì có `loadPage`, CloudStream ưu tiên `loadPage` cho phân trang nếu `search` trả về `Page`.
        // Hiện tại, search chỉ trả về list. Phân trang sẽ dựa vào `loadPage` nếu URL được cấu trúc từ `newPage(..., nextSearchPageUrl)`.
        // Để đơn giản, ta sẽ để `loadPage` xử lý cả URL tìm kiếm.
        val pageActionUrl = "txnhhprovider://loadpage/$searchUrl"
        // Trả về một item giả để CloudStream gọi loadPage
        return listOf(newMovieSearchResponse(query, pageActionUrl, TvType.NSFW) { posterUrl = "" })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement video link extraction
        println("loadLinks called with: $data. Implementation is pending.")
        // Ví dụ (cần HTML thực tế của trang video để lấy link):
        // val document = app.get(data).document
        // val scriptContent = document.select("script:containsData(html5player.setVideoUrlHigh)").html()
        // val videoUrlHigh = Regex("""html5player\.setVideoUrlHigh\(['"](.*?)['"]\)""").find(scriptContent)?.groupValues?.get(1)
        // if (videoUrlHigh != null) {
        // callback(
        // ExtractorLink(
        // source = this.name,
        // name = "Chất lượng Cao",
        // url = videoUrlHigh,
        // referer = data,
        // quality = Qualities.P720.value // Hoặc parse từ tên link nếu có
        // )
        // )
        // return true
        // }
        return false
    }

    private fun getQualityFromString(qualityString: String?): InferredQuality? {
        return qualityString?.filter { it.isDigit() }?.toIntOrNull()?.let {
            InferredQuality.Custom(it)
        }
    }
}

// Data class để giữ kết quả từ parseVideoListPage, tương tự Page của CloudStream nhưng chỉ là helper nội bộ
// data class Page(
//    val url: String, // URL của trang hiện tại đã parse
//    val items: List<SearchResponse>,
//    val nextPageUrl: String?
// )
