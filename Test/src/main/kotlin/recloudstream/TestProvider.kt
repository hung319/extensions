// Khai báo package cho plugin, bạn có thể đổi tên package nếu muốn
package com.lagradost.cloudstream3.plugins.local

// Import các thư viện cần thiết từ CloudStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.parsedSafe
import org.jsoup.nodes.Element

// Tên class chính của provider, CloudStream sẽ tự động tìm và tải class này
class SexTop1 : MainAPI() {
    // Thông tin cơ bản của plugin
    override var mainUrl = "https://sextop1.la"
    override var name = "SexTop1"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Hàm lấy danh sách phim từ trang chủ
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val homePageList = ArrayList<HomePageList>()

        val mainItems = document.select("article.dp-item").mapNotNull {
            it.toSearchResult()
        }

        if (mainItems.isNotEmpty()) {
            homePageList.add(HomePageList("Phim Mới", mainItems))
        }

        return HomePageResponse(homePageList)
    }

    // Hàm chuyển đổi một phần tử HTML thành đối tượng phim
    private fun Element.toSearchResult(): MovieSearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.attr("title")?.trim() ?: return null
        val href = this.selectFirst("a.dp-thumb")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.lazy")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // Hàm xử lý tìm kiếm
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("article.dp-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm tải thông tin chi tiết của phim và link video
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.video-item.dp-entry-box > article > p")?.text()
        val tags = document.select("div.the_tag_list a[rel=tag]").map { it.text() }

        // Phần quan trọng: Lấy ID của video từ HTML
        val postId = document.selectFirst("div#video")?.attr("data-id") ?: return null

        val recommendations = document.select("section.related-movies article.dp-item").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, postId) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // Hàm tải link video (M3U8/MP4)
    override suspend fun loadLinks(
        data: String, // Đây là postId đã lấy ở hàm load
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Trang web dùng AJAX để lấy link, đây là nơi chúng ta giả lập lại yêu cầu đó
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        
        // Dữ liệu gửi đi trong yêu cầu POST.
        // `action` là tham số quan trọng nhất. Tôi "đoán" tên của nó là 'top1tube_get_video'
        // dựa trên tên theme của web. Nếu không chạy, chúng ta sẽ cần tìm tên chính xác.
        val postData = mapOf(
            "action" to "top1tube_get_video",
            "post_id" to data // data ở đây chính là postId
        )

        // Gửi yêu cầu POST và nhận về JSON
        val ajaxResponse = app.post(ajaxUrl, data = postData).parsedSafe<VideoResponse>()

        // Nếu có link trong phản hồi, thêm nó vào trình phát
        ajaxResponse?.link?.let { videoUrl ->
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} Server",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value
                )
            )
        }

        return true
    }

    // Data class để hứng dữ liệu JSON từ AJAX
    data class VideoResponse(
        val status: Boolean?,
        val link: String?,
        val sources: List<VideoSource>?
    )

    data class VideoSource(
        val file: String,
        val label: String,
        val type: String
    )
}
