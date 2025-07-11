package recloudstream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Arrays

class AnimeHayProvider : MainAPI() {

    override var mainUrl = "https://animehay.ceo"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "vi"
    override val hasMainPage = true

    // ===================================================================================
    // *** LOGIC SỬA LỖI VIDEO CHÍNH XÁC ***
    // ===================================================================================

    private fun fixMpegTsStream(body: ResponseBody): ByteArray {
        val source = body.source()
        source.request(Long.MAX_VALUE)
        val buffer: Buffer = source.buffer.clone()
        source.close()
        val byteArray = buffer.readByteArray()
        if (byteArray.isEmpty()) return byteArray
        val packetSize = 188
        val syncByte = 0x47.toByte()
        var validStartPosition = 0
        for (i in 0 until (byteArray.size - packetSize)) {
            if (byteArray[i] == syncByte && byteArray[i + packetSize] == syncByte) {
                validStartPosition = i
                break
            }
        }
        return if (validStartPosition > 0) {
            Log.d(name, "Đã tìm thấy và cắt bỏ $validStartPosition bytes rác.")
            Arrays.copyOfRange(byteArray, validStartPosition, byteArray.size)
        } else {
            byteArray
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain: Interceptor.Chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()
            val needsFix = url.contains("ibyteimg.com") ||
                    url.contains(".tiktokcdn.") ||
                    (url.contains("segment.cloudbeta.win/file/segment/") && !url.contains(".html?token="))
            if (needsFix) {
                val body = response.body
                if (body != null) {
                    try {
                        val fixedBytes = fixMpegTsStream(body)
                        val newBody = ResponseBody.create(body.contentType(), fixedBytes)
                        return@Interceptor response.newBuilder().body(newBody).build()
                    } catch (e: Exception) {
                        Log.e(name, "Lỗi khi đang sửa luồng video", e)
                    } finally {
                        body.close()
                    }
                }
            }
            response
        }
    }

    // ===================================================================================
    // *** CÁC HÀM CƠ BẢN CỦA PROVIDER ***
    // ===================================================================================

    private suspend fun getBaseUrl(): String {
        return mainUrl
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$baseUrl$url"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = getBaseUrl()
        val url = if (page <= 1) baseUrl else "$baseUrl/phim-moi-cap-nhap/trang-$page.html"
        val document = app.get(url).document

        val homePageList = document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse(baseUrl)
        }

        return newHomePageResponse(
            list = HomePageList(name = "Mới Cập Nhật", list = homePageList),
            hasNext = homePageList.isNotEmpty()
        )
    }

    private fun Element.toSearchResponse(baseUrl: String): SearchResponse? {
        val href = fixUrl(this.selectFirst("a")?.attr("href"), baseUrl) ?: return null
        val title = this.selectFirst("div.name-movie")?.text()?.trim() ?: return null
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src"), baseUrl)

        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val baseUrl = getBaseUrl()
        val url = "$baseUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}.html"
        val document = app.get(url).document

        return document.select("div.movies-list div.movie-item").mapNotNull {
            it.toSearchResponse(baseUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        val initialDocument = app.get(url).document

        // **LOGIC SỬA LỖI:**
        // Kiểm tra xem URL hiện tại có phải là trang xem phim không.
        // Nếu đúng, tìm link đến trang thông tin phim và tải lại document từ đó.
        val (documentToParse, seriesUrl) = if (url.contains("/xem-phim/")) {
            val seriesInfoHref = initialDocument.selectFirst("a[href*=/thong-tin-phim/]")?.attr("href")
            val seriesInfoUrl = fixUrl(seriesInfoHref, baseUrl)
            
            if (seriesInfoUrl != null) {
                // Tải document của trang thông tin phim
                Pair(app.get(seriesInfoUrl).document, seriesInfoUrl)
            } else {
                Log.e(name, "Không thể tìm thấy link về trang thông tin phim từ URL: $url")
                return null // Không thể tiếp tục nếu không có trang thông tin
            }
        } else {
            // Nếu không phải trang xem phim, dùng document và url hiện tại
            Pair(initialDocument, url)
        }

        // Từ đây, `documentToParse` chắc chắn là trang thông tin phim
        val title = documentToParse.selectFirst("h1.heading_movie")?.text()?.trim() ?: return null
        val plot = documentToParse.selectFirst("div.desc > div:last-child")?.text()?.trim()
        val posterUrl = fixUrl(documentToParse.selectFirst("div.head div.first img")?.attr("src"), baseUrl)
        val year = documentToParse.selectFirst("div.update_time div:nth-child(2)")?.text()
            ?.trim()?.filter { it.isDigit() }?.toIntOrNull()

        val episodes = documentToParse.select("div.list-item-episode a").mapNotNull {
            fixUrl(it.attr("href"), baseUrl)?.let { epUrl ->
                val epName = it.attr("title").trim().ifBlank { it.text().trim() }
                newEpisode(epUrl) { this.name = epName }
            }
        }.reversed()

        // Nếu không có tập phim, coi như là phim lẻ
        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }

        // Trả về kết quả cho phim bộ
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseUrl = getBaseUrl()
        val document = app.get(data).document
        val scriptContent = document.select("script").firstOrNull { it.data().contains("function loadVideo") }?.data()

        if (scriptContent != null) {
            val tokLink = Regex("tik:\\s*'([^']*)'").find(scriptContent)?.groupValues?.get(1)

            tokLink?.takeIf { it.isNotBlank() }?.let { link ->
                newExtractorLink(
                    source = "Server TOK",
                    name = this.name,
                    url = link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = baseUrl
                    this.quality = Qualities.Unknown.value
                }.let { callback.invoke(it) }
                return true
            }
        }
        return false
    }
}
