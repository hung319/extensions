package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller

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
        val document = app.get(data, interceptor = interceptor).document

        // Bước 1: Trích xuất các mã cần thiết từ khối script toàn cục
        val scriptContent = document.select("script").find { it.data().contains("var YWRzMQo") }?.data()
            ?: throw ErrorLoadingException("Bước 1/3 THẤT BẠI: Không tìm thấy khối script chứa mã.")

        // Regex để trích xuất giá trị của từng biến JS
        val valueCode = Regex("""var\s+YWRzMQo\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1/3 THẤT BẠI: Không trích xuất được 'valueCode'.")
        val code1 = Regex("""var\s+YWRzNA\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1/3 THẤT BẠI: Không trích xuất được 'code1'.")
        val code2 = Regex("""var\s+YWRzNQ\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1/3 THẤT BẠI: Không trích xuất được 'code2'.")
        val code3 = Regex("""var\s+YWRzNg\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1/3 THẤT BẠI: Không trích xuất được 'code3'.")

        // Bước 2: Trích xuất thông tin part và serverId từ các nút bấm
        // Ta chỉ cần 2 giá trị đầu tiên, các mã code trong onclick giờ đã lỗi thời
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
        }.distinct() // Sử dụng distinct để loại bỏ các nút bấm trùng lặp

        if (servers.isEmpty()) {
            throw ErrorLoadingException("Bước 2/3 THẤT BẠI: Không tìm thấy nút chọn server.")
        }

        val ajaxUrl = "$mainUrl/ri3123o235r/"
        val errorMessages = mutableListOf<String>()

        // Bước 3: Gửi AJAX request với các mã đã được cập nhật
        for (server in servers) {
            var responseText = "" // Biến để lưu trữ nội dung response thô
            try {
                val response = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "group" to server.serverId,
                        "part" to server.part,
                        "code" to code1,       // Sử dụng code1 từ script
                        "code2" to code2,      // Sử dụng code2 từ script
                        "code3" to code3,      // Sử dụng code3 từ script
                        "value" to valueCode,  // Sử dụng valueCode từ script
                        "sound" to "av"
                    ),
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
                val res = response.parsed<VideoResponse>()

                if (res.status == "success" && res.data.isNotEmpty()) {
                    val videoUrl = res.data.first()
                    // Load extractor và trả về true nếu thành công
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) {
                        return true 
                    } else {
                        errorMessages.add("Server ${server.serverId} (Part ${server.part}): Extractor thất bại với URL $videoUrl")
                    }
                } else {
                     errorMessages.add("Server ${server.serverId} (Part ${server.part}): API không thành công. Response: $responseText")
                }
            } catch (e: Exception) {
                val detailedError = "Server ${server.serverId} (Part ${server.part}) gặp lỗi: ${e.message}\nAJAX Response (thô): $responseText"
                errorMessages.add(detailedError)
                e.printStackTrace()
            }
        }

        // Nếu tất cả server đều lỗi, ném ra Exception tổng hợp
        throw ErrorLoadingException("Bước 3/3 THẤT BẠI: Tất cả các server đều thất bại. Chi tiết:\n\n${errorMessages.joinToString("\n\n")}")
    }

    data class VideoResponse(
        val status: String,
        val data: List<String>
    )
}
