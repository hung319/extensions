// === File: AnimeVietsubProvider.kt ===
// Version: 2025-05-20 - Thêm phim liên quan, sửa lỗi dataId, cải thiện parse, sửa lỗi loadLinks
package recloudstream // Đảm bảo package name phù hợp với dự án của bạn

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink // Vẫn cần import ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink // Import cái này
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt
import kotlin.text.Regex

// Imports cho RequestBody và MediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

// === Provider Class ===
class AnimeVietsubProvider : MainAPI() {

    private val gson = Gson()
    override var mainUrl = "https://animevietsub.tv"
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "vi"
    override val hasMainPage = true
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.82 Safari/537.36"

    private var currentActiveUrl = mainUrl
    private var domainCheckPerformed = false
    private val domainCheckUrls = listOf("https://animevietsub.tv", "https://bit.ly/animevietsubtv")
    private suspend fun getBaseUrl(): String {
        if (domainCheckPerformed && !currentActiveUrl.contains("animevietsub.tv")) {
            // Nếu đã kiểm tra và currentActiveUrl không còn là link bit.ly (đã được phân giải) thì trả về luôn
            return currentActiveUrl
        }
        // Nếu domainCheckPerformed là true nhưng currentActiveUrl VẪN là bit.ly, có thể lần trước check lỗi, nên check lại.

        var fetchedNewUrl: String? = null
        // Tạo danh sách URL để kiểm tra, ưu tiên currentActiveUrl (có thể đang là bit.ly)
        val urlsToCheck = mutableListOf<String>()
        if (currentActiveUrl.contains("bit.ly") || !domainCheckPerformed) { // Nếu chưa check hoặc current là bit.ly
            urlsToCheck.add(currentActiveUrl) // Thêm bit.ly vào đầu để check
            urlsToCheck.addAll(domainCheckUrls.filter { it != currentActiveUrl }) // Thêm các url khác (nếu có)
        } else {
            urlsToCheck.add(currentActiveUrl) // Nếu đã có url cụ thể, vẫn thử check lại phòng trường hợp domain đó chết
            urlsToCheck.addAll(domainCheckUrls.filter { it != currentActiveUrl }) // Thêm bit.ly và các url khác
        }


        Log.d("AnimeVietsubProvider", "Bắt đầu kiểm tra domain. Các URL sẽ kiểm tra: ${urlsToCheck.distinct()}")

        for (checkUrl in urlsToCheck.distinct()) { // Sử dụng distinct để tránh kiểm tra trùng lặp
            try {
                Log.d("AnimeVietsubProvider", "Đang kiểm tra domain qua $checkUrl")
                // Quan trọng: app.get với allowRedirects = true sẽ tự động theo dõi chuyển hướng của Bitly
                // và response.url sẽ là URL cuối cùng.
                val response = app.get(checkUrl, allowRedirects = true, timeout = 15_000) // Tăng timeout một chút cho Bitly
                val finalUrlString = response.url // Đây sẽ là URL thật sau khi Bitly redirect (ví dụ: https://animevietsub.lol)

                Log.d("AnimeVietsubProvider", "URL '$checkUrl' được phân giải thành '$finalUrlString'")

                val urlObject = URL(finalUrlString)
                val extractedBaseUrl = "${urlObject.protocol}://${urlObject.host}" // Trích xuất protocol và host

                if (extractedBaseUrl.startsWith("http") && !extractedBaseUrl.contains("bit.ly")) { // Đảm bảo URL hợp lệ và không phải là link bit.ly nữa
                    fetchedNewUrl = extractedBaseUrl
                    Log.d("AnimeVietsubProvider", "Đã phân giải thành công $checkUrl sang domain thực: $fetchedNewUrl")
                    break // Tìm thấy URL hoạt động, thoát vòng lặp
                } else if (extractedBaseUrl.contains("bit.ly")) {
                    Log.w("AnimeVietsubProvider", "URL '$checkUrl' vẫn là link bit.ly sau khi get: $finalUrlString. Có thể Bitly chưa chuyển hướng hoặc có lỗi.")
                }
                 else {
                    Log.w("AnimeVietsubProvider", "Lược đồ URL không hợp lệ thu được từ $checkUrl -> $finalUrlString (Base: $extractedBaseUrl)")
                }
            } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Không thể kiểm tra domain từ $checkUrl. Lỗi: ${e.message}")
            }
        }

        if (fetchedNewUrl != null && fetchedNewUrl != currentActiveUrl) {
            Log.i("AnimeVietsubProvider", "Domain đã được cập nhật: $currentActiveUrl -> $fetchedNewUrl")
            currentActiveUrl = fetchedNewUrl
            // Cập nhật cả mainUrl của MainAPI() để các hàm khác sử dụng URL đã được phân giải
            this.mainUrl = currentActiveUrl
        } else if (fetchedNewUrl == null && currentActiveUrl.contains("bit.ly")) {
            // Nếu không fetch được URL mới và currentActiveUrl vẫn là bit.ly -> có lỗi xảy ra
            Log.e("AnimeVietsubProvider", "Tất cả các URL kiểm tra domain đều thất bại và không thể phân giải link Bitly. Giữ nguyên: $currentActiveUrl")
            // Ở đây bạn có thể quyết định ném lỗi nếu muốn người dùng biết không thể truy cập trang
            // throw ErrorLoadingException("Không thể kết nối đến AnimeVietsub qua link rút gọn. Vui lòng thử lại sau.")
        } else if (fetchedNewUrl == null && !currentActiveUrl.contains("bit.ly")) {
            // Không fetch được URL mới, nhưng currentActiveUrl đã là một domain cụ thể (không phải bit.ly)
            Log.w("AnimeVietsubProvider", "Không thể fetch URL mới, nhưng đã có domain cụ thể $currentActiveUrl. Tiếp tục sử dụng domain này.")
            // Vẫn cập nhật mainUrl của provider để đảm bảo nó là domain cụ thể
            this.mainUrl = currentActiveUrl
        }
        else {
             Log.d("AnimeVietsubProvider", "Kiểm tra domain hoàn tất. URL hoạt động hiện tại là: $currentActiveUrl")
             // Đảm bảo mainUrl của provider cũng được cập nhật nếu currentActiveUrl đã được phân giải
             if (!currentActiveUrl.contains("bit.ly")) {
                this.mainUrl = currentActiveUrl
             }
        }
        domainCheckPerformed = true
        // Trả về currentActiveUrl, lúc này nó nên là domain đã được phân giải (ví dụ: https://animevietsub.lol)
        // trừ khi có lỗi nghiêm trọng xảy ra.
        return currentActiveUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val lists = mutableListOf<HomePageList>()
        try {
            val baseUrl = getBaseUrl()
            Log.d("AnimeVietsubProvider", "Đang tải trang chủ từ $baseUrl")
            val document = app.get(baseUrl).document
            document.select("section#single-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Mới cập nhật", it)) }
            document.select("section#new-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Sắp chiếu", it)) }
            document.select("section#hot-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let {
                    val hotListName = document.selectFirst("section#hot-home div.Top a.STPb.Current")?.text() ?: "Đề cử"
                    lists.add(HomePageList(hotListName, it))
                }
            if (lists.isEmpty()) {
                Log.w("AnimeVietsubProvider", "Không tìm thấy danh sách nào trên trang chủ, kiểm tra selector hoặc cấu trúc website.")
                throw ErrorLoadingException("Không thể tải dữ liệu trang chủ. Selector có thể đã thay đổi.")
            }
            return newHomePageResponse(lists, hasNext = false)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong getMainPage", e)
            if (e is ErrorLoadingException) throw e
            throw RuntimeException("Lỗi không xác định khi tải trang chủ: ${e.message}")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/"
            Log.d("AnimeVietsubProvider", "Đang tìm kiếm '$query' bằng URL: $searchUrl")
            val document = app.get(searchUrl).document
            return document.selectFirst("ul.MovieList.Rows")?.select("li.TPostMv")
                ?.mapNotNull { it.toSearchResponse(this, baseUrl) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong search cho query: $query", e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl()
        val infoUrl = url
        val watchPageUrl = if (infoUrl.endsWith("/")) "${infoUrl}xem-phim.html" else "$infoUrl/xem-phim.html"
        Log.d("AnimeVietsubProvider", "Đang tải chi tiết. Info URL: $infoUrl, Watch Page URL: $watchPageUrl")
        try {
            val infoDocument = app.get(infoUrl, headers = mapOf("Referer" to baseUrl)).document
            Log.d("AnimeVietsubProvider", "Đã tải thành công trang thông tin: $infoUrl")
            var watchPageDocument: Document? = null
            try {
                watchPageDocument = app.get(watchPageUrl, referer = infoUrl).document
                Log.d("AnimeVietsubProvider", "Đã tải thành công trang xem phim: $watchPageUrl")
            } catch (e: Exception) {
                Log.w("AnimeVietsubProvider", "Không thể tải trang xem phim ($watchPageUrl), danh sách tập có thể không khả dụng qua phương thức này. Lỗi: ${e.message}")
            }
            return infoDocument.toLoadResponse(this, infoUrl, baseUrl, watchPageDocument)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "LỖI NGHIÊM TRỌNG khi tải trang thông tin chính ($infoUrl)", e)
            return null
        }
    }

    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("article.TPost > a") ?: return null
            val relativeHref = linkElement.attr("href")
            val href = fixUrl(relativeHref, baseUrl) ?: return null
            val title = linkElement.selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val posterUrlRaw = linkElement.selectFirst("div.Image img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)
            val isTvSeries = this.selectFirst("span.mli-eps") != null || this.selectFirst("span.mli-quality") == null
            val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie
            provider.newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi parse item search result: ${this.html()}", e)
            null
        }
    }

    data class EpisodeData(
        val url: String,
        val dataId: String?,
        val duHash: String?
    )

    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String,
        baseUrl: String,
        watchPageDoc: Document?
    ): LoadResponse? {
        val infoDoc = this
        try {
            Log.d("AnimeVietsubProvider", "Đang parse metadata từ trang info: $infoUrl")
            val title = infoDoc.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: run { Log.e("AnimeVietsubProvider", "Không tìm thấy tiêu đề trên trang info $infoUrl"); return null }
            var posterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                ?: infoDoc.selectFirst("meta[property=og:image]")?.attr("content")
            posterUrl = fixUrl(posterUrl, baseUrl)
            val description = infoDoc.selectFirst("div.TPost.Single div.Description")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:description]")?.attr("content")
            val infoSection = infoDoc.selectFirst("div.Info") ?: infoDoc
            val genres = infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=the-loai], div.mvici-left li.AAIco-adjust:contains(Thể loại) a")
                .mapNotNull { it.text()?.trim() }.distinct()
            val yearText = infoSection.select("li:has(strong:containsOwn(Năm))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.selectFirst("p.Info span.Date a")?.text()?.trim()
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()
            val ratingText = infoSection.select("li:has(strong:containsOwn(Điểm))")?.firstOrNull()?.ownText()?.trim()?.substringBefore("/")
                 ?: infoDoc.selectFirst("div.VotesCn div.post-ratings #average_score")?.text()?.trim()
            val rating = ratingText?.toDoubleOrNull()?.toAnimeVietsubRatingInt()
            val statusText = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.select("div.mvici-left li.AAIco-adjust:contains(Trạng thái)")
                    .firstOrNull()?.textNodes()?.lastOrNull()?.text()?.trim()?.replace("Trạng thái:", "")?.trim()
            val status = when {
                statusText?.contains("Đang chiếu", ignoreCase = true) == true || statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                else -> null
            }
            val actors = infoDoc.select("div#MvTb-Cast ul.ListCast li a").mapNotNull { actorElement ->
                val name = actorElement.attr("title").removePrefix("Nhân vật ").trim()
                if (name.isNotBlank()) Actor(name) else null
            }

            Log.d("AnimeVietsubProvider", "Đang parse danh sách tập từ tài liệu trang xem phim (nếu có)...")
            val episodes = if (watchPageDoc != null) {
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode").mapNotNull { epLink ->
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl)
                    val epNameFull = epLink.attr("title").ifBlank { epLink.text() }.trim()
                    val dataId = epLink.attr("data-id").ifBlank { null }
                    val duHash = epLink.attr("data-hash").ifBlank { null }
                    Log.v("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Đang xử lý link: name='$epNameFull', url='$epUrl', dataId='$dataId', hash='$duHash'")
                    val episodeInfoForLoadLinks = EpisodeData(url = epUrl ?: infoUrl, dataId = dataId, duHash = duHash)
                    val episodeNumber = epNameFull.substringBefore("-").replace(Regex("""[^\d]"""), "").toIntOrNull()
                    val cleanEpName = epNameFull.replace(Regex("""^(\d+\s*-\s*|\s*Tập\s*\d+\s*-\s*)"""),"").trim()
                    if (dataId != null && !epNameFull.isNullOrBlank() && epUrl != null) {
                        newEpisode(data = gson.toJson(episodeInfoForLoadLinks)) {
                            this.name = if (cleanEpName.isNotBlank() && !cleanEpName.equals(episodeNumber.toString(), ignoreCase = true) ) {
                                "Tập ${episodeNumber?.toString()?.padStart(2,'0') ?: epNameFull}: $cleanEpName"
                            } else {
                                "Tập ${episodeNumber?.toString()?.padStart(2,'0') ?: epNameFull}"
                            }
                            this.episode = episodeNumber
                        }
                    } else {
                        Log.w("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Bỏ qua tập '$epNameFull': Thiếu thuộc tính bắt buộc (URL, Tên, hoặc **data-id**). Phần tử: ${epLink.outerHtml()}")
                        null
                    }
                }.sortedBy { it.episode ?: Int.MAX_VALUE }
            } else {
                Log.w("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Tài liệu trang xem phim là null. Không thể parse tập phim bằng phương thức cũ.")
                emptyList<Episode>()
            }
            Log.i("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Hoàn tất parse. Tìm thấy ${episodes.size} tập hợp lệ.")

            Log.d("AnimeVietsubProvider", "Đang parse phim đề xuất từ trang info...")
            val recommendations = mutableListOf<SearchResponse>()
            infoDoc.select("div.Wdgt div.MovieListRelated.owl-carousel div.TPostMv").forEach { item ->
                try {
                    val linkElement = item.selectFirst("div.TPost > a")
                    if (linkElement != null) {
                        val recHref = fixUrl(linkElement.attr("href"), baseUrl)
                        val recTitle = linkElement.selectFirst("div.Title")?.text()?.trim()
                        val recPosterUrl = fixUrl(linkElement.selectFirst("div.Image img")?.attr("src"), baseUrl)
                        val isTvSeriesRec = linkElement.selectFirst("span.mli-eps") != null ||
                                           recTitle?.contains("tập", ignoreCase = true) == true ||
                                           linkElement.selectFirst("span.mli-quality") == null
                        val recTvType = if (isTvSeriesRec) TvType.TvSeries else TvType.Movie
                        if (recHref != null && recTitle != null) {
                            recommendations.add(
                                provider.newMovieSearchResponse(recTitle, recHref, recTvType) {
                                    this.posterUrl = recPosterUrl
                                }
                            )
                        } else {
                             Log.w("AnimeVietsubProvider", "[Đề xuất] Bỏ qua item: Thiếu href hoặc title. Phần tử: ${item.html()}")
                        }
                    } else {
                        Log.w("AnimeVietsubProvider", "[Đề xuất] Bỏ qua item: Không tìm thấy phần tử link. Phần tử: ${item.html()}")
                    }
                } catch (e: Exception) {
                    Log.e("AnimeVietsubProvider", "[Đề xuất] Lỗi parse item đề xuất: ${item.html()}", e)
                }
            }
            Log.i("AnimeVietsubProvider", "Tìm thấy ${recommendations.size} phim đề xuất.")

            val isTvSeries = episodes.size > 1 ||
                             (episodes.size == 1 && episodes.firstOrNull()?.name?.contains("Tập", ignoreCase = true) == true) ||
                             infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=anime-bo]").isNotEmpty() ||
                             (statusText?.contains("Phim bộ", ignoreCase = true) == true) ||
                             infoDoc.selectFirst("meta[property=og:type]")?.attr("content") == "video.tv_show"

            return if (isTvSeries) {
                Log.d("AnimeVietsubProvider", "Tạo TvSeriesLoadResponse cho '$title'")
                provider.newTvSeriesLoadResponse(title, infoUrl, TvType.TvSeries, episodes = episodes) {
                    this.posterUrl = posterUrl; this.plot = description; this.tags = genres; this.year = year; this.rating = rating; this.showStatus = status;
                    addActors(actors)
                    this.recommendations = recommendations
                }
            } else {
                Log.d("AnimeVietsubProvider", "Tạo MovieLoadResponse cho '$title'")
                val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                    ?: infoDoc.select("ul.InfoList li.AAIco-adjust:contains(Thời lượng)")
                        .firstOrNull()?.ownText()?.trim()
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()
                val movieDataForLoadLinks = if (episodes.isNotEmpty()) {
                    val firstEpisodeDataString = episodes.first().data
                    try {
                        val parsedEpisodeData = gson.fromJson(firstEpisodeDataString, EpisodeData::class.java)
                        if (parsedEpisodeData.dataId != null) {
                            Log.d("AnimeVietsubProvider", "Sử dụng dữ liệu từ tập đầu tiên cho MovieLoadResponse: $firstEpisodeDataString")
                            firstEpisodeDataString
                        } else {
                            Log.w("AnimeVietsubProvider", "Dữ liệu tập đầu tiên của phim không chứa dataId. Tạo fallback. Data: $firstEpisodeDataString")
                            val moviePageDataIdAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")
                                ?.substringAfterLast("a")?.substringBefore("/")
                                ?: infoUrl.substringAfterLast("a").substringBefore("/")
                            Log.d("AnimeVietsubProvider", "DataId thử nghiệm cho phim (fallback): $moviePageDataIdAttempt")
                            gson.toJson(EpisodeData(url = infoUrl, dataId = moviePageDataIdAttempt, duHash = null))
                        }
                    } catch (e: Exception) {
                        Log.e("AnimeVietsubProvider", "Không thể parse EpisodeData từ tập đầu tiên của phim. Tạo fallback. Data: $firstEpisodeDataString", e)
                        val moviePageDataIdAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")
                                ?.substringAfterLast("a")?.substringBefore("/")
                                ?: infoUrl.substringAfterLast("a").substringBefore("/")
                        Log.d("AnimeVietsubProvider", "DataId thử nghiệm cho phim (sau lỗi parse): $moviePageDataIdAttempt")
                        gson.toJson(EpisodeData(url = infoUrl, dataId = moviePageDataIdAttempt, duHash = null))
                    }
                } else {
                    Log.w("AnimeVietsubProvider", "Không tìm thấy tập nào cho phim '$title'. Tạo fallback EpisodeData cho loadLinks.")
                     val moviePageDataIdAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")
                                ?.substringAfterLast("a")?.substringBefore("/")
                                ?: infoUrl.substringAfterLast("a").substringBefore("/")
                    Log.d("AnimeVietsubProvider", "DataId thử nghiệm cho phim (không có tập): $moviePageDataIdAttempt")
                    gson.toJson(EpisodeData(url = infoUrl, dataId = moviePageDataIdAttempt, duHash = null))
                }
                provider.newMovieLoadResponse(title, infoUrl, TvType.Movie, movieDataForLoadLinks) {
                    this.posterUrl = posterUrl; this.plot = description; this.tags = genres; this.year = year; this.rating = rating; durationMinutes?.let { addDuration(it.toString()) }
                    addActors(actors)
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong toLoadResponse xử lý cho url: $infoUrl", e); return null
        }
    }

    private data class AjaxPlayerResponse(
        @JsonProperty("success") val success: Int? = null,
        @JsonProperty("link") val link: List<LinkSource>? = null
    )
    private data class LinkSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )

        override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val baseUrl = getBaseUrl()
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a"
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt" // API này có thể thay đổi
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()

        Log.d("AnimeVietsubProvider", "LoadLinks nhận được data: $data")

        val episodeData = try {
            gson.fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể parse EpisodeData JSON trong loadLinks: '$data'", e)
            return false
        }

        val episodePageUrl = episodeData.url
        val episodeId = episodeData.dataId
        val episodeHash = episodeData.duHash

        if (episodeId == null || episodePageUrl.isBlank()) {
            Log.e("AnimeVietsubProvider", "Thiếu ID tập phim (dataId) hoặc URL trang tập phim trong episode data: $data. Không thể tiếp tục.")
            return false
        }

        Log.i("AnimeVietsubProvider", "Đang xử lý ID tập phim: $episodeId bằng phương thức API cho URL: $episodePageUrl")

        try {
            val postData = mutableMapOf(
                "id" to episodeId,
                "play" to "api"
            )
            if (!episodeHash.isNullOrBlank()) {
                postData["link"] = episodeHash
            }

            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodePageUrl
            )

            Log.d("AnimeVietsubProvider", "POSTing đến AnimeVietsub AJAX API: $ajaxUrl với data: $postData")
            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl)
            Log.d("AnimeVietsubProvider", "AnimeVietsub AJAX API Response Status: ${ajaxResponse.code}")

            val playerResponse = try {
                gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java)
            } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Không thể parse JSON từ ajax/player: ${ajaxResponse.text}", e); null
            }

            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
                Log.e("AnimeVietsubProvider", "Request ajax/player thất bại hoặc response không hợp lệ: ${ajaxResponse.text}")
                return false
            }
            
            playerResponse.link.forEach { linkSource ->
                val dataEncOrDirectLink = linkSource.file
                if (dataEncOrDirectLink.isNullOrBlank()) {
                    Log.w("AnimeVietsubProvider", "Bỏ qua link source vì 'file' rỗng hoặc null.")
                    return@forEach // Bỏ qua link source này và tiếp tục với link source tiếp theo
                }

                val finalStreamUrl: String
                if (dataEncOrDirectLink.startsWith("http") && (dataEncOrDirectLink.contains(".m3u8") || dataEncOrDirectLink.contains(".mp4"))) {
                    // Nếu là link trực tiếp (ít khả năng với AnimeVietsub hiện tại cho video chính)
                    finalStreamUrl = dataEncOrDirectLink
                    Log.i("AnimeVietsubProvider", "Lấy được link trực tiếp từ API: $finalStreamUrl")
                } else {
                    // Nếu là dataEnc, cần giải mã
                    Log.d("AnimeVietsubProvider", "Lấy được 'dataenc': ${dataEncOrDirectLink.take(50)}...")
                    Log.d("AnimeVietsubProvider", "POSTing 'dataenc' đến API giải mã: $decryptApiUrl")
                    
                    val requestBody = dataEncOrDirectLink.toRequestBody(textPlainMediaType)
                    val decryptHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to episodePageUrl // Hoặc ajaxUrl, tùy theo yêu cầu của API giải mã
                    )
                    val decryptResponse = app.post(
                        decryptApiUrl,
                        headers = decryptHeaders,
                        requestBody = requestBody
                    )
                    Log.d("AnimeVietsubProvider", "API giải mã Response Status: ${decryptResponse.code}")
                    // Log.v("AnimeVietsubProvider", "API giải mã Response Body: ${decryptResponse.text}") // Log nếu cần debug kỹ

                    finalStreamUrl = decryptResponse.text.trim()
                }

                if (finalStreamUrl.startsWith("http") && (finalStreamUrl.endsWith(".m3u8") || finalStreamUrl.contains(".mp4")) ) {
                    Log.i("AnimeVietsubProvider", "Đã lấy thành công link M3U8/MP4 cuối cùng: $finalStreamUrl")

                    val streamTypeCalculated = if (finalStreamUrl.endsWith(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO // Sử dụng VIDEO cho MP4 trực tiếp
                    }

                    val requiredStreamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Origin" to baseUrl, // Origin nên là baseUrl của trang web
                        "Referer" to episodePageUrl // Referer cho stream có thể là trang tập phim hoặc ajaxUrl
                    )

                    // Sử dụng newExtractorLink với initializer lambda
                    val extractorLink = newExtractorLink(
                        source = this.name, // Sử dụng tên provider (this.name)
                        name = "AnimeVietsub API" + (linkSource.label?.let { " - $it" } ?: ""),
                        url = finalStreamUrl,
                        type = streamTypeCalculated // Truyền type đã tính toán
                    ) {
                        // Bên trong lambda này, 'this' chính là đối tượng ExtractorLink đang được tạo
                        this.referer = episodePageUrl // Đặt referer bên trong initializer
                        this.quality = Qualities.Unknown.value // Hoặc parse từ linkSource.label nếu có
                        this.headers = requiredStreamHeaders // Đặt headers
                        // Các thuộc tính khác có thể được đặt ở đây nếu cần
                        // ví dụ: this.extractorData = "dữ liệu gì đó nếu cần"
                    }
                    callback(extractorLink)
                    foundLinks = true
                } else {
                    Log.e("AnimeVietsubProvider", "API giải mã không trả về URL M3U8/MP4 hợp lệ. Response: '$finalStreamUrl'")
                }
            }

        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong quá trình trích xuất link API cho ID tập phim $episodeId", e)
            // Cân nhắc throw ErrorLoadingException("Không thể tải link phim: ${e.message}") để người dùng biết
        }

        if (!foundLinks) {
            Log.w("AnimeVietsubProvider", "Không có link stream nào được trích xuất thành công cho ID tập phim $episodeId ($episodePageUrl)")
        }
        return foundLinks
    }

    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try {
            URLEncoder.encode(this, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể URL encode: $this", e)
            this
        }
    }

    private fun Double?.toAnimeVietsubRatingInt(): Int? {
        return this?.let { (it * 100).roundToInt().coerceIn(0, 1000) }
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> URL(URL(baseUrl), url).toString()
                else -> URL(URL(baseUrl), "/$url".removePrefix("//")).toString()
            }
        } catch (e: java.net.MalformedURLException) {
            Log.e("AnimeVietsubProvider", "URL không hợp lệ khi fix: base='$baseUrl', url='$url'", e)
            if (url.startsWith("http")) return url
            null
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi không xác định khi fix URL: base='$baseUrl', url='$url'", e)
            null
        }
    }
}
