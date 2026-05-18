package recloudstream // Bạn nhớ sửa lại tên package cho khớp với project của bạn nhé

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

// =====================================================================
// 1. EXTRACTOR TÙY CHỈNH (Hỗ trợ Vite SPA dùng WebView)
// =====================================================================
class ViteModernPlayerExtractor : ExtractorApi() {
    override var name = "Kr21/Turtle (Auto)"
    override var mainUrl = "https://kr21.click" 
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        // Thiết lập WebView ẩn để tải trang web Vite/React/Vue
        val resolver = WebViewResolver(
            interceptUrl = Regex(".*\\.(m3u8|mp4|m3u|mpd).*"),
            useOkhttp = false, // Bắt buộc false để trình duyệt chạy full mã JS
            timeout = 15000L   // Chờ tối đa 15s để JS gọi API lấy link
        )

        try {
            // Mở link iframe và bắt lại request stream
            val (request, _) = resolver.resolveUsingWebView(
                url = url,
                referer = referer ?: "https://kurakura21.com/"
            )

            val streamUrl = request?.url

            if (!streamUrl.isNullOrEmpty()) {
                // Xác định loại stream
                val linkType = when {
                    streamUrl.contains(".m3u8") || streamUrl.contains(".m3u") -> ExtractorLinkType.M3U8
                    streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                // Tạo ExtractorLink bằng DSL mới của CloudStream
                links.add(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = linkType
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return links
    }
}

// =====================================================================
// 2. PROVIDER CHÍNH
// =====================================================================
class KuraKura21Provider : MainAPI() {
    override var name = "KuraKura21"
    override var mainUrl = "https://kurakura21.com"
    override var lang = "id"
    override var hasMainPage = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // --- Helper Methods ---
    private fun String.addPage(page: Int): String {
        return if (page > 1) "${this.removeSuffix("/")}/page/$page/" else this
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href")
        val title = this.selectFirst(".entry-title a, .gmr-box-content h2 a")?.text() 
            ?: link.attr("title") 
            ?: "N/A"
        
        // Logic lấy ảnh tối ưu cho Flying Press (Lazy Load)
        val imgTag = this.selectFirst("img")
        val posterUrl = imgTag?.attr("data-src")
            ?.takeIf { it.isNotEmpty() }
            ?: imgTag?.attr("src")

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.NSFW
        ) {
            this.posterUrl = posterUrl
        }
    }

    // --- Main Logic ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        
        // Mục Recent Post hỗ trợ phân trang khi kéo xuống
        val recentDoc = app.get(mainUrl.addPage(page)).document
        val recentItems = recentDoc.select(".gmr-grid .item").mapNotNull { it.toSearchResult() }
        items.add(HomePageList("Recent Posts", recentItems))

        // Các mục tĩnh khác chỉ load ở trang 1 để chống giật lag / limit IP
        if (page == 1) {
            val sections = listOf(
                Pair("Best Rating", "$mainUrl/best-rating/"),
                Pair("18+ Sub Indo", "$mainUrl/tag/18-sub-indo/"),
                Pair("Jav Sub Indo", "$mainUrl/genre/jav-sub-indo/"),
                Pair("Korea 18+", "$mainUrl/genre/korea-18/")
            )

            coroutineScope {
                sections.map { (sectionName, sectionUrl) ->
                    async {
                        try {
                            val doc = app.get(sectionUrl).document
                            val res = doc.select(".gmr-grid .item").mapNotNull { it.toSearchResult() }
                            if (res.isNotEmpty()) HomePageList(sectionName, res) else null
                        } catch (e: Exception) { null }
                    }
                }.awaitAll().filterNotNull().forEach { items.add(it) }
            }
        }

        return newHomePageResponse(items, hasNext = recentItems.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select(".gmr-grid .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Không có tiêu đề"
        
        val imgTag = document.selectFirst(".gmr-movie-data img, .content-thumbnail img")
        val poster = imgTag?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: imgTag?.attr("src")
        
        val description = document.selectFirst(".entry-content p")?.text()?.trim()
        val tags = document.select(".gmr-moviedata a[rel=tag]").map { it.text() }

        val recommendations = document.select(".gmr-grid:has(.gmr-related-title) .item, #gmr-related .item").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url
        ) {
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
        val document = app.get(data, referer = mainUrl).document

        // Tìm Post ID an toàn (fallback 3 lớp)
        val postId = document.selectFirst("body")?.classNames()
            ?.find { it.startsWith("postid-") }
            ?.removePrefix("postid-")
            ?: document.selectFirst("link[rel='shortlink']")?.attr("href")?.substringAfter("?p=")
            ?: document.selectFirst("input[name='comment_post_ID']")?.attr("value")
            ?: return false

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val tabs = document.select(".muvipro-player-tabs li a, .gmr-player-nav li a")

        coroutineScope {
            tabs.map { tab ->
                async {
                    try {
                        val tabId = tab.attr("href").removePrefix("#")
                        if (tabId.isNotEmpty()) {
                            val postData = mapOf(
                                "action" to "muvipro_player_content",
                                "tab" to tabId,
                                "post_id" to postId
                            )
                            
                            val playerContent = app.post(
                                url = ajaxUrl,
                                data = postData,
                                referer = data,
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                            ).document

                            val rawIframeSrc = playerContent.selectFirst("iframe")?.attr("src") ?: return@async
                            val iframeSrc = fixUrl(rawIframeSrc) 
                            
                            // PHÂN LOẠI XỬ LÝ LINK
                            if (iframeSrc.contains("kr21.click") || iframeSrc.contains("turtle4up.top")) {
                                // 1. Chạy qua Extractor Vite bằng WebView ẩn
                                val extractor = ViteModernPlayerExtractor()
                                val links = extractor.getUrl(iframeSrc, data)
                                links.forEach { callback.invoke(it) }
                            } else {
                                // 2. Giao cho các Extractor mặc định của CloudStream xử lý
                                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }
        
        return true
    }
}
