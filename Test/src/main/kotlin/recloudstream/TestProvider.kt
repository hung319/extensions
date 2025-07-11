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

    override var mainUrl = "https://animehay.tv"
    override var name = "AnimeHay"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "vi"
    override val hasMainPage = true

    // ===================================================================================
    // *** LOGIC SỬA LỖI VIDEO KẾT HỢP ***
    // ===================================================================================

    /**
     * Lớp 1: Tìm và loại bỏ các byte rác ở đầu luồng video MPEG-TS.
     */
    private fun removeGarbageBytes(byteArray: ByteArray): ByteArray {
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
            Log.d(name, "Bước 1: Đã cắt bỏ $validStartPosition bytes rác.")
            Arrays.copyOfRange(byteArray, validStartPosition, byteArray.size)
        } else {
            byteArray
        }
    }

    /**
     * Lớp 2: Giải mã dữ liệu đã được làm sạch bằng thuật toán XOR.
     */
    private fun decryptXOR(data: ByteArray): ByteArray {
        val key = byteArrayOf(55, 33, 2, 1, 5, 4, 9, 8, 99, 3, 2, 5, 7, 8, 9, 10)
        val limit = 1024
        val length = if (data.size < limit) data.size else limit
        for (i in 0 until length) {
            data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        Log.d(name, "Bước 2: Đã giải mã XOR $length bytes.")
        return data
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
                        val originalBytes = body.bytes()
                        // Áp dụng 2 lớp bảo vệ
                        val cleanedBytes = removeGarbageBytes(originalBytes)
                        val decryptedBytes = decryptXOR(cleanedBytes)
                        
                        val newBody = ResponseBody.create(body.contentType(), decryptedBytes)
                        return@Interceptor response.newBuilder().body(newBody).build()
                    } catch (e: Exception) {
                        Log.e(name, "Lỗi khi đang xử lý luồng video", e)
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
            url.startsWith("/") -> "$baseUrl$url"
            else -> null
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

        val (documentToParse, seriesUrl) = if (url.contains("/xem-phim/")) {
            val seriesInfoHref = initialDocument.selectFirst("a[href*=/thong-tin-phim/]")?.attr("href")
            val seriesInfoUrl = fixUrl(seriesInfoHref, baseUrl)
            if (seriesInfoUrl != null) {
                Pair(app.get(seriesInfoUrl).document, seriesInfoUrl)
            } else {
                Log.e(name, "Không thể tìm thấy link về trang thông tin phim từ URL: $url")
                return null
            }
        } else {
            Pair(initialDocument, url)
        }

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

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, seriesUrl, TvType.AnimeMovie, seriesUrl) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }

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
