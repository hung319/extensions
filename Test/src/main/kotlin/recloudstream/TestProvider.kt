// Package recloudstream
package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import android.util.Base64

class Av123Provider : MainAPI() {
    override var mainUrl = "https://www1.123av.com"
    override var name = "123AV"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "dm5/new-release" to "Mới phát hành",
        "dm6/recent-update" to "Cập nhật gần đây",
        "dm5/trending" to "Đang thịnh hành"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/$lang/${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.box-item-list div.box-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val hrefRaw = a.attr("href")
        val href = if (hrefRaw.startsWith("http")) {
            hrefRaw
        } else {
            "$mainUrl/$lang/${hrefRaw.removePrefix("/")}"
        }

        if (href.isBlank()) return null

        val title = this.selectFirst("div.detail a")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("div.thumb img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/$lang/search?keyword=$query"
        val document = app.get(url).document

        return document.select("div.box-item-list div.box-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("span.genre a").map { it.text() }
        val description = document.selectFirst("div.description p")?.text()

        val movieId = document.selectFirst("#page-video")
            ?.attr("v-scope")
            ?.substringAfter("Movie({id: ")
            ?.substringBefore(",")
            ?.trim()
            ?: document.selectFirst(".favourite")
            ?.attr("v-scope")
            ?.substringAfter("movie', ")
            ?.substringBefore(",")
            ?.trim()
            ?: return null

        return newMovieLoadResponse(title, url, TvType.NSFW, movieId) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }
    
    // ##### CẬP NHẬT LOGIC GỠ LỖI #####
    private fun xorDecode(input: String): String {
        val key = "MW4gC3v5a1q2E6Z"
        var decodedString = ""
        try {
            val decodedBytes = Base64.decode(input, Base64.DEFAULT)
            decodedString = String(decodedBytes, Charsets.ISO_8859_1)
            
            val result = StringBuilder()
            for (i in decodedString.indices) {
                result.append((decodedString[i].code xor key[i % key.length].code).toChar())
            }
            return result.toString()
        } catch (e: Exception) {
            // Văng lỗi với thông tin chi tiết nhất có thể để gỡ lỗi
            throw Exception("Lỗi xorDecode. Input: '$input'. Key: '$key'. DecodedString: '$decodedString'. Error: ${e.message}")
        }
    }
    
    private fun deobfuscateScript(p: String, a: Int, c: Int, k: List<String>): String {
        var pStr = p
        val d = mutableMapOf<String, String>()

        fun e(c_val: Int): String {
            return if (c_val < a) "" else e(c_val / a) +
                    (if (c_val % a > 35) (c_val % a + 29).toChar().toString() else (c_val % a).toString(36))
        }

        var cVar = c
        while (cVar-- > 0) {
            d[e(cVar)] = if (k.getOrNull(cVar) != null && k[cVar].isNotEmpty()) k[cVar] else e(cVar)
        }
        
        for (key in d.keys) {
            pStr = pStr.replace(Regex("\\b$key\\b"), d[key]!!)
        }
        return pStr
    }

    override suspend fun loadLinks(
        data: String, // movieId
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Giữ lại try-catch để bắt lỗi tổng thể
        try {
            val apiUrl = "$mainUrl/$lang/ajax/v/$data/videos"
            val apiRes = app.get(apiUrl).parsedSafe<ApiResponse>()
                ?: throw Exception("API trả về null hoặc không thể phân tích cú pháp.")

            apiRes.result?.watch?.forEach { watchItem ->
                val encodedUrl = watchItem.url ?: return@forEach
                val decodedPath = xorDecode(encodedUrl)
                
                if (decodedPath.isBlank() || !decodedPath.startsWith("/")) {
                    throw Exception("Đường dẫn giải mã không hợp lệ. Kết quả: '$decodedPath'")
                }
                
                // Host của iframe đã đổi thành player.123av.me
                val iframeUrl = "https://player.123av.me$decodedPath"
                val iframeContent = app.get(iframeUrl, referer = mainUrl).text
                
                val evalRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{(.+?)\((.+)\)\)""")
                val match = evalRegex.find(iframeContent) ?: throw Exception("Không tìm thấy script eval trong iframe.")
                val captured = match.groupValues[2]

                val paramsRegex = Regex("""'(.+)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""")
                val paramsMatch = paramsRegex.find(captured) ?: throw Exception("Không tìm thấy tham số script.")

                val p = paramsMatch.groupValues[1]
                val a = paramsMatch.groupValues[2].toInt()
                val c = paramsMatch.groupValues[3].toInt()
                val k = paramsMatch.groupValues[4].split("|")

                val deobfuscated = deobfuscateScript(p, a, c, k)
                
                val m3u8Regex = Regex("""(https?://[^\s'"]+\.m3u8)""")
                val m3u8Match = m3u8Regex.find(deobfuscated)
                val m3u8Url = m3u8Match?.groupValues?.get(1) ?: throw Exception("Không tìm thấy link m3u8. Script đã giải mã: $deobfuscated")


                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "123AV",
                        url = m3u8Url,
                        referer = iframeUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
            return true
        } catch (e: Exception) {
            throw Exception("Lỗi trong loadLinks: ${e.message}")
        }
    }

    data class WatchItem(val url: String?)
    data class ApiResult(val watch: List<WatchItem>?)
    data class ApiResponse(val result: ApiResult?)
}
