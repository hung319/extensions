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

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("span.title")?.text()?.trim() ?: "Unknown Title"
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("data-src")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
    
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.thumb-block").map {
            it.toSearchResult()
        }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name]")?.text() ?: "No title"
        val poster = document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
        val description = document.selectFirst("div.video-description")?.text()
        val tags = document.select("div.video-tags a.label").mapNotNull { it.text() }

        val iframeUrl = document.selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("No video iframe found on page")

        return newMovieLoadResponse(title, url, TvType.NSFW, iframeUrl) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // Đưa hàm unpack trở lại để xử lý trình phát davioad.com
    private fun unpack(p: String, a: Int, c: Int, k: List<String>): String {
        var pMut = p
        var cMut = c
        
        fun intToBase(n: Int, base: Int): String {
            return n.toString(base)
        }

        while (cMut-- > 0) {
            val token = intToBase(cMut, a)
            val replacement = if (k.getOrNull(cMut)?.isNotEmpty() == true) k[cMut] else token
            pMut = pMut.replace(Regex("\\b$token\\b"), replacement)
        }
        return pMut
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' là iframeUrl, ví dụ: https://hglink.to/e/kk28e54hxf11
        val hglinkUrl = data

        // Theo dõi chuyển hướng để lấy trang player cuối cùng
        val playerResponse = app.get(hglinkUrl, referer = mainUrl)
        val playerPageUrl = playerResponse.url
        val playerDocument = playerResponse.document

        var streamUrl: String? = null
        var sourceName = "Unknown"

        // **LOGIC MỚI: Thử cả hai phương pháp**

        // **Phương pháp 1: Kiểm tra `data-hash` (cho turboviplay)**
        val streamUrlFromDataHash = playerDocument.selectFirst("div#video_player")?.attr("data-hash")
        if (!streamUrlFromDataHash.isNullOrBlank()) {
            streamUrl = streamUrlFromDataHash
            sourceName = "TurboViPlay"
        } else {
            // **Phương pháp 2: Nếu không có data-hash, thử giải mã packer (cho davioad)**
            val scriptContent = playerDocument.select("script").map { it.data() }.firstOrNull { 
                it.contains("eval(function(p,a,c,k,e,d)") 
            }

            if (scriptContent != null) {
                val regex = Regex("""}\('(.+)',(\d+),(\d+),'(.+?)'\.split""")
                val match = regex.find(scriptContent)
                
                if (match != null) {
                    val (p, aStr, cStr, kStr) = match.destructured
                    val unpackedJs = unpack(p, aStr.toInt(), cStr.toInt(), kStr.split("|"))

                    val hlsRegex = Regex("""file:"([^"]+m3u8)"""")
                    val hlsMatch = hlsRegex.find(unpackedJs)
                    val streamPath = hlsMatch?.groupValues?.get(1)
                    
                    if (streamPath != null) {
                         streamUrl = if (streamPath.startsWith("http")) {
                            streamPath
                        } else {
                            "https:$streamPath"
                        }
                        sourceName = "Davioad"
                    }
                }
            }
        }

        // Nếu sau khi thử cả 2 cách vẫn không có link -> báo lỗi
        if (streamUrl == null) {
            throw ErrorLoadingException("Could not find video stream URL using any known method")
        }
        
        callback.invoke(
            ExtractorLink(
                source = sourceName,
                name = "Fanxxx Stream",
                url = streamUrl,
                referer = playerPageUrl, // Referer là trang player cuối cùng
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
