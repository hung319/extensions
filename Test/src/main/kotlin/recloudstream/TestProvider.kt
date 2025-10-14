package recloudstream

// Đảm bảo bạn đã thêm thư viện Jsoup vào build.gradle
// implementation "org.jsoup:jsoup:1.15.3"

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        // Sử dụng data-src vì ảnh được lazy-load
        val posterUrl = this.selectFirst("img")?.attr("data-src")
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
     * Hàm giải mã packer của Javascript.
     * Đây là phần cốt lõi để xử lý logic obfuscate của trang davioad.
     */
    private fun unpack(p: String, a: Int, c: Int, k: List<String>): String {
        var pMut = p
        var cMut = c
        
        // Hàm nội bộ để chuyển đổi số sang chuỗi theo cơ số `a`
        fun intToBase(n: Int, base: Int): String {
            return n.toString(base)
        }

        while (cMut-- > 0) {
            val token = intToBase(cMut, a)
            val replacement = if (k.getOrNull(cMut)?.isNotEmpty() == true) k[cMut] else token
            // Sử dụng regex để đảm bảo chỉ thay thế toàn bộ từ (whole word)
            pMut = pMut.replace(Regex("\\b$token\\b"), replacement)
        }
        return pMut
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
        // `data` chính là iframeUrl từ hàm load
        val hglinkUrl = data

        // Theo dõi chuyển hướng từ hglink.to -> davioad.com
        val davioadResponse = app.get(hglinkUrl, referer = mainUrl)
        val davioadUrl = davioadResponse.url
        val document = davioadResponse.document

        // Tìm đoạn script chứa packer
        val scriptContent = document.select("script").map { it.data() }.firstOrNull { 
            it.contains("eval(function(p,a,c,k,e,d)") 
        } ?: throw ErrorLoadingException("Packed script not found on davioad page")

        // Dùng regex để bóc tách các tham số của packer
        val regex = Regex("""}\('(.+)',(\d+),(\d+),'(.+?)'\.split""")
        val match = regex.find(scriptContent) 
            ?: throw ErrorLoadingException("Failed to extract packer arguments")

        val (p, aStr, cStr, kStr) = match.destructured
        val a = aStr.toInt()
        val c = cStr.toInt()
        val k = kStr.split("|")

        // Giải mã để lấy ra script gốc
        val unpackedJs = unpack(p, a, c, k)

        // Dùng regex để tìm link m3u8 trong script đã giải mã
        val hlsRegex = Regex("""file:"([^"]+m3u8)"""")
        val hlsMatch = hlsRegex.find(unpackedJs) 
            ?: throw ErrorLoadingException("HLS link not found in unpacked script")
            
        val streamPath = hlsMatch.groupValues[1]
        // Đôi khi link trả về không có domain, cần nối lại
        val streamUrl = if (streamPath.startsWith("http")) {
            streamPath
        } else {
            "https:$streamPath" // Mặc định là https nếu không có scheme
        }
        
        // Gửi link đã xử lý về cho player
        callback.invoke(
            ExtractorLink(
                source = "Davioad",
                name = "Fanxxx Stream",
                url = streamUrl,
                referer = davioadUrl, // Referer là yếu tố bắt buộc
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf(
                    // Các headers này giúp giả lập một trình duyệt thật
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                )
            )
        )

        return true
    }
}
