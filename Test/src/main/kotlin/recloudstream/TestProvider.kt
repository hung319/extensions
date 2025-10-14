package recloudstream

// Đảm bảo bạn đã thêm thư viện Jsoup vào build.gradle
// implementation "org.jsoup:jsoup:1.15.3"

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Coder's Note:
 * - mainUrl: URL gốc của trang web.
 * - name: Tên của provider sẽ hiển thị trong CloudStream.
 * - supportedTypes: Loại nội dung mà provider hỗ trợ. Ở đây là phim người lớn.
 */
class FanxxxProvider : MainAPI() {
    override var mainUrl = "https://fanxxx.org"
    override var name = "Fanxxx"
    override val hasMainPage = true
    override var lang = "zh" // Nội dung chủ yếu là tiếng Trung
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    /**
     * Hàm helper để chuyển đổi một phần tử HTML (Element) thành kết quả tìm kiếm (SearchResponse).
     * Giúp tái sử dụng code cho cả trang chủ và trang tìm kiếm.
     */
    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("span.title")?.text()?.trim() ?: "Unknown Title"
        val href = this.selectFirst("a")!!.attr("href")
        // SỬA LỖI: Xử lý URL tương đối (protocol-relative) cho poster.
        val posterUrl = this.selectFirst("img")?.attr("data-src")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
    
    /**
     * Lấy danh sách phim từ trang chủ.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val homePageList = document.select("article.thumb-block").map {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList("Newest Videos", homePageList),
            hasNext = true
        )
    }

    /**
     * Thực hiện tìm kiếm phim theo từ khóa.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.thumb-block").map {
            it.toSearchResult()
        }
    }
    
    /**
     * Tải thông tin chi tiết của một bộ phim.
     * Hàm này sẽ lấy URL của iframe và truyền nó cho `loadLinks` qua biến `data`.
     */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name]")?.text() ?: "No title"
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
        val description = document.selectFirst("div.video-description")?.text()
        val tags = document.select("div.video-tags a.label").mapNotNull { it.text() }

        // Lấy link iframe, đây là bước quan trọng để đến được link video
        val iframeUrl = document.selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("No video iframe found on page")

        return newMovieLoadResponse(title, url, TvType.NSFW, iframeUrl) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }
    
    /**
     * Tải các liên kết xem phim (m3u8).
     * @param data Đây là URL của iframe được truyền từ hàm `load`.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // SỬA LỖI: Logic hoàn toàn mới để xử lý trang player `turboviplay.com`.
        // 'data' chính là iframeUrl từ hàm load(), ví dụ: https://turbovidhls.com/t/68a83109e0e15
        val playerPageUrl = data

        // Tải nội dung trang player
        val playerDocument = app.get(playerPageUrl, referer = mainUrl).document

        // Trích xuất link m3u8 từ thuộc tính 'data-hash'
        val streamUrl = playerDocument.selectFirst("div#video_player")?.attr("data-hash")
            ?: throw ErrorLoadingException("Could not find video stream URL in player page")

        // Gửi link đã xử lý về cho player
        callback.invoke(
            ExtractorLink(
                source = "TurboViPlay",
                name = "Fanxxx Stream",
                url = streamUrl,
                referer = playerPageUrl, // Referer là trang player
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                ),
                extractorData = null
            )
        )

        return true
    }
}
