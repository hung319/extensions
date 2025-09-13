package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller
import android.util.Log

class JavmostProvider : MainAPI() {
    override var mainUrl = "https://www5.javmost.com"
    override var name = "Javmost"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    private val interceptor = CloudflareKiller()
    override val mainPage = mainPageOf(
        "category/all" to "Latest",
        "topview" to "Most Viewed",
        "topdaily" to "Daily Top",
        "topweek" to "Weekly Top",
        "topmonth" to "Monthly Top",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url, interceptor = interceptor).document
        val home = document.select("div.col-md-4.col-sm-6").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h1.card-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.lazyload")?.let {
            it.parent()?.selectFirst("source")?.attr("data-srcset")
        }
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl, interceptor = interceptor).document
        return document.select("div.col-md-4.col-sm-6").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.page-header")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.card.bg-black img.lazyload")?.let {
             it.parent()?.selectFirst("source")?.attr("data-srcset")
        }
        val description = document.selectFirst("div.card-block a h2")?.text()
        
        val tags = document.select("p.card-text i.fa-tag ~ a").map { it.text() }
        val genres = document.select("p.card-text i.ion-ios-videocam ~ a").map { it.text() }
        
        val actors = document.select("p.card-text i.fa-female ~ a").map {
            Actor(it.text(), it.attr("href"))
        }

        val recommendations = document.select("div.card-group div.card").mapNotNull {
            val recTitle = it.selectFirst("h2.card-title")?.text() ?: return@mapNotNull null
            val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = it.selectFirst("source")?.attr("data-srcset")
            newMovieSearchResponse(recTitle, recHref, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags + genres
            this.actors = actors.map { ActorData(it) }
            this.recommendations = recommendations
        }
    }

    private val TAG = "JavmostProvider" // Tag để lọc log cho dễ

    // Dữ liệu chỉ cần part và serverId từ nút bấm
    private data class ServerButtonData(
        val part: String,
        val serverId: String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "--- Bắt đầu loadLinks cho url: $data ---")

        val document = app.get(data, interceptor = interceptor).document
        Log.d(TAG, "Đã tải xong document HTML.")

        // Bước 1: Trích xuất các mã cần thiết từ khối script toàn cục
        Log.d(TAG, "Bước 1: Bắt đầu trích xuất mã từ script...")
        val scriptContent = document.select("script").find { it.data().contains("var YWRzMQo") }?.data()
            ?: throw ErrorLoadingException("Bước 1 THẤT BẠI: Không tìm thấy khối script chứa mã.")
        // Log.d(TAG, "Nội dung script tìm thấy: $scriptContent") // Bỏ comment nếu muốn xem toàn bộ script

        val valueCode = Regex("""var\s+YWRzMQo\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1 THẤT BẠI: Không trích xuất được 'valueCode'.")
        Log.d(TAG, "-> valueCode (YWRzMQo): $valueCode")

        val code1 = Regex("""var\s+YWRzNA\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1 THẤT BẠI: Không trích xuất được 'code1' (YWRzNA).")
        Log.d(TAG, "-> code1 (YWRzNA): $code1")

        val code2 = Regex("""var\s+YWRzNQ\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1 THẤT BẠI: Không trích xuất được 'code2' (YWRzNQ).")
        Log.d(TAG, "-> code2 (YWRzNQ): $code2")

        val code3 = Regex("""var\s+YWRzNg\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1 THẤT BẠI: Không trích xuất được 'code3' (YWRzNg).")
        Log.d(TAG, "-> code3 (YWRzNg): $code3")
        Log.d(TAG, "Bước 1: Trích xuất mã thành công.")

        // Bước 2: Trích xuất thông tin part và serverId từ các nút bấm
        Log.d(TAG, "Bước 2: Bắt đầu tìm và phân tích các nút server...")
        val servers = document.select("ul.nav-tabs-inverse li button[onclick], span.partlist button[onclick]").mapNotNull {
            val onclick = it.attr("onclick")
            val regex = Regex("""select_part\('([^']+)','([^']+)'.*?\)""")
            val match = regex.find(onclick)
            if (match != null) {
                val (part, serverId) = match.destructured
                ServerButtonData(part, serverId)
            } else {
                null
            }
        }.distinct()

        if (servers.isEmpty()) {
            throw ErrorLoadingException("Bước 2 THẤT BẠI: Không tìm thấy nút chọn server nào.")
        }
        Log.d(TAG, "Bước 2: Tìm thấy ${servers.size} server/part duy nhất: $servers")

        val ajaxUrl = "$mainUrl/ri3123o235r/"
        val errorMessages = mutableListOf<String>()

        Log.d(TAG, "Bước 3: Bắt đầu lặp qua các server để lấy link video...")
        // Bước 3: Gửi AJAX request với các mã đã được cập nhật
        for (server in servers) {
            var responseText = ""
            Log.d(TAG, "--- Đang thử Server: ${server.serverId}, Part: ${server.part} ---")
            try {
                val postData = mapOf(
                    "group" to server.serverId,
                    "part" to server.part,
                    "code" to code1,
                    "code2" to code2,
                    "code3" to code3,
                    "value" to valueCode,
                    "sound" to "av"
                )
                Log.d(TAG, "Payload gửi đi: $postData")

                val response = app.post(
                    ajaxUrl,
                    data = postData,
                    headers = mapOf(
                        "Referer" to data,
                        "Origin" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                    ),
                    interceptor = interceptor
                )
                
                responseText = response.text
                Log.d(TAG, "Response (thô) từ server: $responseText")

                val res = response.parsed<VideoResponse>()

                if (res.status == "success" && res.data.isNotEmpty()) {
                    val videoUrl = res.data.first()
                    Log.d(TAG, "API trả về thành công! URL video: $videoUrl")
                    Log.d(TAG, "Bắt đầu gọi loadExtractor...")
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) {
                        Log.d(TAG, "--- loadLinks THÀNH CÔNG! Đã tìm thấy link. ---")
                        return true 
                    } else {
                        val errorMsg = "Server ${server.serverId} (Part ${server.part}): Extractor thất bại với URL $videoUrl"
                        Log.w(TAG, errorMsg)
                        errorMessages.add(errorMsg)
                    }
                } else {
                     val errorMsg = "Server ${server.serverId} (Part ${server.part}): API không thành công. Status: ${res.status}, Message: ${res.msg}, Response: $responseText"
                     Log.w(TAG, errorMsg)
                     errorMessages.add(errorMsg)
                }
            } catch (e: Exception) {
                val detailedError = "Server ${server.serverId} (Part ${server.part}) gặp lỗi Exception: ${e::class.simpleName} -> ${e.message}"
                Log.e(TAG, detailedError, e) // In cả stacktrace của lỗi
                errorMessages.add("$detailedError\nAJAX Response (thô): $responseText")
            }
        }

        val finalErrorMessage = "Bước 3/3 THẤT BẠI: Tất cả các server đều thất bại. Chi tiết:\n\n${errorMessages.joinToString("\n\n")}"
        Log.e(TAG, finalErrorMessage)
        throw ErrorLoadingException(finalErrorMessage)
    }

    data class VideoResponse(
    val status: String,
    val msg: String?, // Thêm dòng này
    val data: List<String>
  )
}
