package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.withTimeoutOrNull
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

    // FIX 1: Chuyển TAG vào companion object
    companion object {
        private const val TAG = "JavmostProvider"
    }

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
    
    private fun parseDetailPageAsSearchResult(document: Document, url: String): SearchResponse? {
        val title = document.selectFirst("h1.page-header")?.text()?.trim()
        if (title.isNullOrBlank()) return null

        val poster = document.selectFirst("div.card.bg-black img.lazyload")?.let {
             it.parent()?.selectFirst("source")?.attr("data-srcset")
        }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        Log.d(TAG, "Searching with URL: $searchUrl")

        val response = withTimeoutOrNull(15000) {
            app.get(searchUrl, interceptor = interceptor)
        }

        if (response == null) {
            Log.e(TAG, "Search request timed out or failed for query: $query")
            return emptyList()
        }

        val document = response.document

        if (!response.url.contains("/search/")) {
            Log.d(TAG, "Search redirected to a single result page: ${response.url}")
            
            val singleResult = parseDetailPageAsSearchResult(document, response.url)
            
            return if (singleResult != null) {
                Log.d(TAG, "Successfully parsed single result page.")
                listOf(singleResult)
            } else {
                Log.w(TAG, "Failed to parse single result page. It might be blank or has a different structure.")
                emptyList()
            }
        } else {
            Log.d(TAG, "Search returned a standard list page.")
            return document.select("div.col-md-4.col-sm-6").mapNotNull {
                it.toSearchResult()
            }
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

    private data class ServerButtonData(
        val part: String,
        val serverId: String,
    )

    data class VideoResponse(
        val status: String,
        val msg: String?,
        val data: List<String>
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "--- Bắt đầu loadLinks cho url: $data ---")

        val document = try {
            app.get(data, interceptor = interceptor).document
        } catch (e: Exception) {
            // CHUYỂN SANG LOG.E VÀ THROW
            val errorMsg = "Lỗi Mạng: Không tải được trang chi tiết."
            Log.e(TAG, "$errorMsg URL: $data", e)
            throw ErrorLoadingException(errorMsg)
        }

        if (document.body().html().isBlank()) {
            // CHUYỂN SANG LOG.E VÀ THROW
            val errorMsg = "Lỗi: Server trả về trang rỗng."
            Log.e(TAG, "$errorMsg URL: $data")
            throw ErrorLoadingException(errorMsg)
        }

        val errorMessages = mutableListOf<String>()
        var foundLink = false

        try {
            val scriptContent = document.select("script:containsData(var YWRzMQo)").first()?.data()
                ?: throw ErrorLoadingException("Không tìm thấy script chứa mã")

            val valueCode = Regex("""var\s+YWRzMQo\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("Không lấy được 'valueCode'")
            val code1 = Regex("""var\s+YWRzNA\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("Không lấy được 'code1'")
            val code2 = Regex("""var\s+YWRzNQ\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("Không lấy được 'code2'")
            val code3 = Regex("""var\s+YWRzNg\s*=\s*'([^']+)';""").find(scriptContent)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("Không lấy được 'code3'")

            val servers = document.select("ul.nav-tabs-inverse li button[onclick], span.partlist button[onclick]").mapNotNull {
                Regex("""select_part\('([^']+)','([^']+)'.*?\)""").find(it.attr("onclick"))?.destructured
            }.distinct()

            if (servers.isEmpty()) throw ErrorLoadingException("Không tìm thấy nút chọn server")

            val ajaxUrl = "$mainUrl/ri3123o235r/"
            
            run loop@{
                servers.forEach { (part, serverId) ->
                    try {
                        val postData = mapOf(
                            "group" to serverId, "part" to part, "code" to code1,
                            "code2" to code2, "code3" to code3, "value" to valueCode, "sound" to "av"
                        )
                        val res = app.post(ajaxUrl, data = postData, headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")).parsed<VideoResponse>()

                        if (res.status == "success" && res.data.isNotEmpty()) {
                            val intermediateUrl = res.data.first()
                            Log.d(TAG, "Got intermediate URL: $intermediateUrl, passing to loadExtractor")
                            
                            if (loadExtractor(intermediateUrl, data, subtitleCallback, callback)) {
                                Log.d(TAG, "loadExtractor successfully handled the link.")
                                foundLink = true
                                return@loop
                            } else {
                                errorMessages.add("S${serverId}P${part}: loadExtractor thất bại")
                            }
                        } else {
                            errorMessages.add("S${serverId}P${part}: API lỗi")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing server S${serverId}P${part}", e)
                        errorMessages.add("S${serverId}P${part}: Exception")
                    }
                }
            }
        } catch (e: Exception) {
            // CHUYỂN SANG LOG.E VÀ THROW
            val errorMsg = "Lỗi Parse: ${e.message}"
            Log.e(TAG, errorMsg, e)
            throw ErrorLoadingException(errorMsg)
        }

        if (!foundLink) {
            // CHUYỂN SANG LOG.E VÀ THROW
            val finalErrorMessage = "Tất cả server đều thất bại. (${errorMessages.joinToString(", ")})"
            Log.e(TAG, finalErrorMessage)
            throw ErrorLoadingException(finalErrorMessage)
        }
        
        return true
    }
}
