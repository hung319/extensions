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

    private data class ServerData(
        val part: String,
        val serverId: String,
        val code1: String,
        val code2: String,
        val code3: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        
        val scriptContent = document.select("script").find { it.data().contains("var YWRzMQo") }?.data()
        val valueCode = Regex("""var\s+YWRzMQo\s*=\s*'([^']+)';""").find(scriptContent ?: "")?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Bước 1/3 THẤT BẠI: Không thể trích xuất mã 'valueCode'.")

        val servers = document.select("ul.nav-tabs-inverse li button[onclick]").mapNotNull {
            val onclick = it.attr("onclick")
            val regex = Regex("""select_part\('(\d+)','(\d+)',this,'parent','([^']+)','([^']+)','([^']+)'\)""")
            val match = regex.find(onclick)
            if (match != null) {
                val (part, serverId, code1, code2, code3) = match.destructured
                ServerData(part, serverId, code1, code2, code3)
            } else {
                null
            }
        }

        if (servers.isEmpty()) {
            throw ErrorLoadingException("Bước 2/3 THẤT BẠI: Không tìm thấy nút chọn server.")
        }

        val ajaxUrl = "$mainUrl/ri3123o235r/"
        val errorMessages = mutableListOf<String>()

        for (server in servers) {
            var responseText = "" // Biến để lưu trữ nội dung response thô
            try {
                val response = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "group" to server.serverId, "part" to server.part, "code" to server.code1,
                        "code2" to server.code2, "code3" to server.code3, "value" to valueCode,
                        "sound" to "av"
                    ),
                    headers = mapOf(
                        "Referer" to data, "Origin" to mainUrl, "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                    ),
                    interceptor = interceptor
                )
                
                // Luôn lấy nội dung text của response để ghi log
                responseText = response.text
                val res = response.parsed<VideoResponse>()

                if (res.status == "success" && res.data.isNotEmpty()) {
                    val videoUrl = res.data.first()
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) {
                        return true // Thành công, thoát
                    } else {
                        errorMessages.add("Server ${server.serverId}: Extractor thất bại với URL $videoUrl")
                    }
                } else {
                     errorMessages.add("Server ${server.serverId}: API không thành công. Response: $responseText")
                }
            } catch (e: Exception) {
                // Ghi lại lỗi và nội dung response thô (nếu có)
                val detailedError = "Server ${server.serverId} gặp lỗi: ${e.message}\nAJAX Response (thô): $responseText"
                errorMessages.add(detailedError)
                e.printStackTrace() // Vẫn in stacktrace ra logcat nếu có thể
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
