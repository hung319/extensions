// === File: AnimeVietsubProvider.kt ===
// Version: 2025-05-20 - Thêm phim liên quan, sửa lỗi dataId, cải thiện parse
package recloudstream // Đảm bảo package name phù hợp với dự án của bạn

// === Imports ===
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors // Thêm cái này nếu bạn muốn parse diễn viên/nhân vật
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
// import com.lagradost.cloudstream3.utils.loadExtractor // Cần nếu muốn thêm fallback
// import com.lagradost.cloudstream3.utils.newExtractorLink // Dù không dùng nữa nhưng có thể cần cho fallback
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty // Cần cho data class nếu dùng Jackson thay Gson
import com.google.gson.Gson // Cần cho Gson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt
import kotlin.text.Regex

// Imports cho RequestBody và MediaType (Cần cho loadLinks)
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

// === Provider Class ===
class AnimeVietsubProvider : MainAPI() {

    // Tạo một instance Gson để tái sử dụng
    private val gson = Gson()

    // Thông tin cơ bản của Provider
    override var mainUrl = "https://animevietsub.lol" // URL mặc định, sẽ được cập nhật bởi getBaseUrl
    override var name = "AnimeVietsub"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "vi"
    override val hasMainPage = true
    // User agent chung, có thể bạn muốn tùy chỉnh
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.82 Safari/537.36"


    // --- Phần xử lý domain động ---
    private var currentActiveUrl = mainUrl // Khởi tạo bằng mainUrl
    private var domainCheckPerformed = false
    private val domainCheckUrls = listOf("https://animevietsub.lol", "https://animevietsub.tv") // Có thể thêm mirror khác vào đây
    private suspend fun getBaseUrl(): String {
        // Chỉ kiểm tra một lần mỗi khi khởi tạo provider instance
        if (domainCheckPerformed) return currentActiveUrl
        var fetchedNewUrl: String? = null
        // Ưu tiên kiểm tra currentActiveUrl trước, sau đó đến các URL khác trong domainCheckUrls
        val urlsToCheck = listOf(currentActiveUrl) + domainCheckUrls.filter { it != currentActiveUrl }
        Log.d("AnimeVietsubProvider", "Bắt đầu kiểm tra domain. Các URL sẽ kiểm tra: $urlsToCheck")
        for (checkUrl in urlsToCheck) {
            try {
                Log.d("AnimeVietsubProvider", "Đang kiểm tra domain qua $checkUrl")
                val response = app.get(checkUrl, allowRedirects = true, timeout = 10_000) // Thêm timeout ngắn (10 giây)
                val finalUrlString = response.url
                val urlObject = URL(finalUrlString) // Tạo đối tượng URL từ URL cuối cùng sau khi redirect
                val extractedBaseUrl = "${urlObject.protocol}://${urlObject.host}" // Lấy protocol và host

                if (extractedBaseUrl.startsWith("http")) { // Đảm bảo URL hợp lệ
                    fetchedNewUrl = extractedBaseUrl
                    Log.d("AnimeVietsubProvider", "Đã phân giải thành công $checkUrl sang $fetchedNewUrl")
                    break // Tìm thấy URL hoạt động, thoát vòng lặp
                } else {
                    Log.w("AnimeVietsubProvider", "Lược đồ URL không hợp lệ thu được từ $checkUrl -> $finalUrlString")
                }
            } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Không thể kiểm tra domain từ $checkUrl. Lỗi: ${e.message}")
            }
        }

        if (fetchedNewUrl != null && fetchedNewUrl != currentActiveUrl) {
            Log.i("AnimeVietsubProvider", "Domain đã được cập nhật: $currentActiveUrl -> $fetchedNewUrl")
            currentActiveUrl = fetchedNewUrl
            mainUrl = currentActiveUrl // Cập nhật cả mainUrl của provider
        } else if (fetchedNewUrl == null) {
            Log.w("AnimeVietsubProvider", "Tất cả các URL kiểm tra domain đều thất bại. Sử dụng URL đã biết cuối cùng: $currentActiveUrl")
            // Bạn có thể ném lỗi ở đây nếu muốn thông báo cho người dùng rằng không thể kết nối đến trang web
            // throw ErrorLoadingException("Không thể kết nối đến AnimeVietsub. Vui lòng thử lại sau.")
        } else {
             Log.d("AnimeVietsubProvider", "Kiểm tra domain hoàn tất. URL hoạt động hiện tại vẫn là: $currentActiveUrl")
        }
        domainCheckPerformed = true
        return currentActiveUrl
    }
    // --- Kết thúc phần xử lý domain động ---

    // === Trang chủ ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false) // Trang chủ không phân trang

        val lists = mutableListOf<HomePageList>()
        try {
            val baseUrl = getBaseUrl() // Lấy URL đã được kiểm tra
            Log.d("AnimeVietsubProvider", "Đang tải trang chủ từ $baseUrl")
            val document = app.get(baseUrl).document

            // Parse các section trên trang chủ
            // Ví dụ: Phim mới cập nhật (selector có thể thay đổi)
            document.select("section#single-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Mới cập nhật", it)) }

            // Ví dụ: Phim sắp chiếu
            document.select("section#new-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let { lists.add(HomePageList("Sắp chiếu", it)) }

            // Ví dụ: Phim đề cử/hot (selector cho tên danh sách có thể thay đổi)
            document.select("section#hot-home ul.MovieList.Rows li.TPostMv")
                .mapNotNull { it.toSearchResponse(this, baseUrl) }
                .takeIf { it.isNotEmpty() }?.let {
                    val hotListName = document.selectFirst("section#hot-home div.Top a.STPb.Current")?.text() ?: "Đề cử"
                    lists.add(HomePageList(hotListName, it))
                }
            
            // Kiểm tra nếu không có danh sách nào được parse
            if (lists.isEmpty()) {
                Log.w("AnimeVietsubProvider", "Không tìm thấy danh sách nào trên trang chủ, kiểm tra selector hoặc cấu trúc website.")
                // Có thể trả về lỗi thay vì list rỗng để người dùng biết
                throw ErrorLoadingException("Không thể tải dữ liệu trang chủ. Selector có thể đã thay đổi.")
            }
            return newHomePageResponse(lists, hasNext = false)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong getMainPage", e)
            if (e is ErrorLoadingException) throw e // Ném lại lỗi đã biết
            throw RuntimeException("Lỗi không xác định khi tải trang chủ: ${e.message}") // Ném lỗi chung
        }
    }

    // === Tìm kiếm ===
    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val baseUrl = getBaseUrl()
            // AnimeVietsub sử dụng /tim-kiem/keyword/
            val searchUrl = "$baseUrl/tim-kiem/${query.encodeUri()}/" // Mã hóa query
            Log.d("AnimeVietsubProvider", "Đang tìm kiếm '$query' bằng URL: $searchUrl")
            val document = app.get(searchUrl).document

            // Parse kết quả từ selector ul.MovieList.Rows li.TPostMv
            return document.selectFirst("ul.MovieList.Rows")?.select("li.TPostMv")
                ?.mapNotNull { it.toSearchResponse(this, baseUrl) } ?: emptyList()
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong search cho query: $query", e)
            return emptyList() // Trả về rỗng nếu có lỗi
        }
    }

    // === Lấy chi tiết ===
    override suspend fun load(url: String): LoadResponse? {
        val baseUrl = getBaseUrl() // Đảm bảo base URL được cập nhật
        val infoUrl = url // URL trang thông tin chính (ví dụ: /phim/ten-phim-axxxx/)
        // URL trang xem phim thường có dạng /phim/ten-phim-axxxx/xem-phim.html
        val watchPageUrl = if (infoUrl.endsWith("/")) "${infoUrl}xem-phim.html" else "$infoUrl/xem-phim.html"
        Log.d("AnimeVietsubProvider", "Đang tải chi tiết. Info URL: $infoUrl, Watch Page URL: $watchPageUrl")

        try {
            // 1. Tải trang thông tin chính
            val infoDocument = app.get(infoUrl, headers = mapOf("Referer" to baseUrl)).document
            Log.d("AnimeVietsubProvider", "Đã tải thành công trang thông tin: $infoUrl")

            // 2. Tải trang xem phim (để lấy danh sách tập theo logic cũ, vì trang info không có data-id, data-hash)
            var watchPageDocument: Document? = null
            try {
                // Thêm referer là trang info để mô phỏng hành vi người dùng
                watchPageDocument = app.get(watchPageUrl, referer = infoUrl).document
                Log.d("AnimeVietsubProvider", "Đã tải thành công trang xem phim: $watchPageUrl")
            } catch (e: Exception) {
                Log.w("AnimeVietsubProvider", "Không thể tải trang xem phim ($watchPageUrl), danh sách tập có thể không khả dụng qua phương thức này. Lỗi: ${e.message}")
                // Không ném lỗi ở đây, vẫn tiếp tục thử parse thông tin từ infoDocument
            }

            // 3. Gọi toLoadResponse với cả hai document
            return infoDocument.toLoadResponse(this, infoUrl, baseUrl, watchPageDocument)

        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "LỖI NGHIÊM TRỌNG khi tải trang thông tin chính ($infoUrl)", e)
            return null // Nếu trang info chính lỗi thì không thể tiếp tục
        }
    }

    // === Helper parse Item (Element) thành SearchResponse ===
    private fun Element.toSearchResponse(provider: MainAPI, baseUrl: String): SearchResponse? {
        return try {
            val linkElement = this.selectFirst("article.TPost > a") ?: return null
            val relativeHref = linkElement.attr("href")
            val href = fixUrl(relativeHref, baseUrl) ?: return null // Sửa URL nếu cần
            val title = linkElement.selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            // Lấy poster từ data-src nếu có, fallback về src
            val posterUrlRaw = linkElement.selectFirst("div.Image img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
            val posterUrl = fixUrl(posterUrlRaw, baseUrl)

            // Xác định TvType: Kiểm tra span.mli-eps (số tập) hoặc span.mli-quality (chất lượng, thường cho movie)
            val isTvSeries = this.selectFirst("span.mli-eps") != null || this.selectFirst("span.mli-quality") == null
            val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie // Mặc định là TvSeries nếu không rõ

            provider.newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                // Thêm các thông tin khác nếu có từ HTML (ví dụ: chất lượng, tập mới nhất)
                // val quality = element.selectFirst("span.mli-quality")?.text()?.trim()
                // val episode = element.selectFirst("span.mli-eps")?.text()?.trim()
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi parse item search result: ${this.html()}", e)
            null
        }
    }

    // Data class để lưu thông tin episode, truyền qua loadLinks
    data class EpisodeData(
        val url: String, // URL của trang tập phim (dùng làm referer)
        val dataId: String?, // ID của tập phim (data-id)
        val duHash: String?  // Hash của tập phim (data-hash)
    )

    // === Helper parse Document thành LoadResponse ===
    private suspend fun Document.toLoadResponse(
        provider: MainAPI,
        infoUrl: String,     // URL của trang thông tin phim
        baseUrl: String,     // Base URL của trang web
        watchPageDoc: Document? // Document của trang xem phim (có thể null)
    ): LoadResponse? {
        val infoDoc = this // Document trang info
        try {
            // --- Parse thông tin phim từ infoDoc ---
            Log.d("AnimeVietsubProvider", "Đang parse metadata từ trang info: $infoUrl")
            val title = infoDoc.selectFirst("div.TPost.Single div.Title")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Tập")?.trim()
                ?: run { Log.e("AnimeVietsubProvider", "Không tìm thấy tiêu đề trên trang info $infoUrl"); return null }

            var posterUrl = infoDoc.selectFirst("div.TPost.Single div.Image img")?.attr("src")
                ?: infoDoc.selectFirst("meta[property=og:image]")?.attr("content")
            posterUrl = fixUrl(posterUrl, baseUrl)

            val description = infoDoc.selectFirst("div.TPost.Single div.Description")?.text()?.trim()
                ?: infoDoc.selectFirst("meta[property=og:description]")?.attr("content")

            // Khu vực chứa thông tin chi tiết, có thể là div.Info hoặc toàn bộ document
            val infoSection = infoDoc.selectFirst("div.Info") ?: infoDoc

            val genres = infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=the-loai], div.mvici-left li.AAIco-adjust:contains(Thể loại) a")
                .mapNotNull { it.text()?.trim() }.distinct() // Lấy cả từ cấu trúc cũ và mới (load.html)

            val yearText = infoSection.select("li:has(strong:containsOwn(Năm))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.selectFirst("p.Info span.Date a")?.text()?.trim() // Từ load.html <span class="Date AAIco-date_range"><a...>2025</a></span>
            val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

            val ratingText = infoSection.select("li:has(strong:containsOwn(Điểm))")?.firstOrNull()?.ownText()?.trim()?.substringBefore("/")
                 ?: infoDoc.selectFirst("div.VotesCn div.post-ratings #average_score")?.text()?.trim() // Từ load.html
            val rating = ratingText?.toDoubleOrNull()?.toAnimeVietsubRatingInt()

            val statusText = infoSection.select("li:has(strong:containsOwn(Trạng thái))")?.firstOrNull()?.ownText()?.trim()
                ?: infoDoc.select("div.mvici-left li.AAIco-adjust:contains(Trạng thái)") // Từ load.html
                    .firstOrNull()?.textNodes()?.lastOrNull()?.text()?.trim()?.replace("Trạng thái:", "")?.trim()

            val status = when {
                statusText?.contains("Đang chiếu", ignoreCase = true) == true || statusText?.contains("Đang tiến hành", ignoreCase = true) == true -> ShowStatus.Ongoing
                statusText?.contains("Hoàn thành", ignoreCase = true) == true -> ShowStatus.Completed
                else -> null
            }
            
            val actors = infoDoc.select("div#MvTb-Cast ul.ListCast li a").mapNotNull { actorElement ->
                val name = actorElement.attr("title").removePrefix("Nhân vật ").trim()
                // val image = fixUrl(actorElement.selectFirst("img")?.attr("src"), baseUrl)
                // ActorData(name, image) // Nếu bạn muốn lưu cả ảnh
                if (name.isNotBlank()) Actor(name) else null
            }


            // --- Parse danh sách tập phim từ watchPageDoc (nếu có) ---
            Log.d("AnimeVietsubProvider", "Đang parse danh sách tập từ tài liệu trang xem phim (nếu có)...")
            val episodes = if (watchPageDoc != null) {
                watchPageDoc.select("div.server ul.list-episode li a.btn-episode").mapNotNull { epLink ->
                    val epUrl = fixUrl(epLink.attr("href"), baseUrl) // URL của trang tập phim, dùng làm referer
                    val epNameFull = epLink.attr("title").ifBlank { epLink.text() }.trim() // Ví dụ: "Tập 01 - Tên tập" hoặc "01"
                    val dataId = epLink.attr("data-id").ifBlank { null } // Lấy data-id
                    val duHash = epLink.attr("data-hash").ifBlank { null } // Lấy data-hash (nếu có)

                    Log.v("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Đang xử lý link: name='$epNameFull', url='$epUrl', dataId='$dataId', hash='$duHash'")
                    
                    // Tạo đối tượng EpisodeData để truyền cho loadLinks
                    val episodeInfoForLoadLinks = EpisodeData(url = epUrl ?: infoUrl, dataId = dataId, duHash = duHash) // Nếu epUrl null, fallback về infoUrl
                    
                    // Trích xuất số tập từ tên
                    val episodeNumber = epNameFull.substringBefore("-").replace(Regex("""[^\d]"""), "").toIntOrNull()
                    val cleanEpName = epNameFull.replace(Regex("""^(\d+\s*-\s*|\s*Tập\s*\d+\s*-\s*)"""),"").trim() // Bỏ "Tập X - "

                    // Chỉ tạo episode nếu có dataId (cần cho loadLinks) và tên/url hợp lệ
                    if (dataId != null && !epNameFull.isNullOrBlank() && epUrl != null) {
                        newEpisode(data = gson.toJson(episodeInfoForLoadLinks)) { // Truyền JSON của EpisodeData
                            this.name = if (cleanEpName.isNotBlank() && !cleanEpName.equals(episodeNumber.toString(), ignoreCase = true) ) {
                                "Tập ${episodeNumber?.toString()?.padStart(2,'0') ?: epNameFull}: $cleanEpName"
                            } else {
                                "Tập ${episodeNumber?.toString()?.padStart(2,'0') ?: epNameFull}"
                            }
                            this.episode = episodeNumber
                            // Các thông tin khác như poster, rating cho từng tập có thể thêm ở đây nếu có
                        }
                    } else {
                        Log.w("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Bỏ qua tập '$epNameFull': Thiếu thuộc tính bắt buộc (URL, Tên, hoặc **data-id**). Phần tử: ${epLink.outerHtml()}")
                        null
                    }
                }.sortedBy { it.episode ?: Int.MAX_VALUE } // Sắp xếp theo số tập
            } else {
                Log.w("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Tài liệu trang xem phim là null. Không thể parse tập phim bằng phương thức cũ.")
                emptyList<Episode>() // Trả về danh sách rỗng nếu không có watchPageDoc
            }
            Log.i("AnimeVietsubProvider", "[Parse Tập - Trang Xem Phim] Hoàn tất parse. Tìm thấy ${episodes.size} tập hợp lệ.")

            // +++ Parse "Phim liên quan" (Recommendations) từ infoDoc +++
            Log.d("AnimeVietsubProvider", "Đang parse phim đề xuất từ trang info...")
            val recommendations = mutableListOf<SearchResponse>()
            // Selector từ load.html cho "Phim liên quan"
            infoDoc.select("div.Wdgt div.MovieListRelated.owl-carousel div.TPostMv").forEach { item ->
                try {
                    val linkElement = item.selectFirst("div.TPost > a")
                    if (linkElement != null) {
                        val recHref = fixUrl(linkElement.attr("href"), baseUrl)
                        val recTitle = linkElement.selectFirst("div.Title")?.text()?.trim()
                        val recPosterUrl = fixUrl(linkElement.selectFirst("div.Image img")?.attr("src"), baseUrl)
                        
                        val isTvSeriesRec = linkElement.selectFirst("span.mli-eps") != null || // Có span số tập
                                           recTitle?.contains("tập", ignoreCase = true) == true || // Tiêu đề chứa "tập"
                                           linkElement.selectFirst("span.mli-quality") == null // Không có span chất lượng (thường cho movie)
                        val recTvType = if (isTvSeriesRec) TvType.TvSeries else TvType.Movie

                        if (recHref != null && recTitle != null) {
                            recommendations.add(
                                provider.newMovieSearchResponse(recTitle, recHref, recTvType) {
                                    this.posterUrl = recPosterUrl
                                    // Ghi chú: Cloudstream thường hiển thị recommendations dưới dạng danh sách ngang,
                                    // nên các trường như plot, rating của từng recommendation có thể không hiển thị.
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
            // +++ KẾT THÚC PARSE PHIM LIÊN QUAN +++


            // --- Xác định loại TV/Movie và trả về response ---
            // Điều kiện isTvSeries có thể cần tinh chỉnh thêm dựa trên cấu trúc trang
            val isTvSeries = episodes.size > 1 ||
                             (episodes.size == 1 && episodes.firstOrNull()?.name?.contains("Tập", ignoreCase = true) == true) ||
                             infoSection.select("li:has(strong:containsOwn(Thể loại)) a[href*=anime-bo]").isNotEmpty() ||
                             (statusText?.contains("Phim bộ", ignoreCase = true) == true) ||
                             infoDoc.selectFirst("meta[property=og:type]")?.attr("content") == "video.tv_show"


            return if (isTvSeries) {
                Log.d("AnimeVietsubProvider", "Tạo TvSeriesLoadResponse cho '$title'")
                provider.newTvSeriesLoadResponse(title, infoUrl, TvType.TvSeries, episodes = episodes) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = genres
                    this.year = year
                    this.rating = rating
                    this.showStatus = status
                    addActors(actors) // Thêm diễn viên/nhân vật
                    this.recommendations = recommendations // Thêm danh sách phim liên quan
                }
            } else { // Mặc định là Movie nếu không phải TvSeries
                Log.d("AnimeVietsubProvider", "Tạo MovieLoadResponse cho '$title'")
                val durationText = infoSection.select("li:has(strong:containsOwn(Thời lượng))")?.firstOrNull()?.ownText()?.trim()
                    ?: infoDoc.select("ul.InfoList li.AAIco-adjust:contains(Thời lượng)") // Từ load.html
                        .firstOrNull()?.ownText()?.trim()
                val durationMinutes = durationText?.filter { it.isDigit() }?.toIntOrNull()

                // Dữ liệu cho loadLinks của Movie
                // Nếu là phim lẻ và có 1 episode với dataId, dùng nó.
                // Nếu không, tạo fallback EpisodeData.
                val movieDataForLoadLinks = if (episodes.isNotEmpty()) {
                    val firstEpisodeDataString = episodes.first().data // Lấy chuỗi JSON từ Episode.data
                    try {
                        val parsedEpisodeData = gson.fromJson(firstEpisodeDataString, EpisodeData::class.java)
                        if (parsedEpisodeData.dataId != null) {
                            Log.d("AnimeVietsubProvider", "Sử dụng dữ liệu từ tập đầu tiên cho MovieLoadResponse: $firstEpisodeDataString")
                            firstEpisodeDataString // Dùng chuỗi JSON gốc
                        } else {
                            Log.w("AnimeVietsubProvider", "Dữ liệu tập đầu tiên của phim không chứa dataId. Tạo fallback. Data: $firstEpisodeDataString")
                            // Thử lấy dataId từ link xem phim trên trang info
                            val moviePageDataIdAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")
                                ?.substringAfterLast("a")?.substringBefore("/") // Lấy ID từ URL phim, ví dụ: a5630
                                ?: infoUrl.substringAfterLast("a").substringBefore("/") // Fallback từ URL chính
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
                } else { // Không có episode nào được list (ví dụ: phim lẻ chiếu trực tiếp)
                    Log.w("AnimeVietsubProvider", "Không tìm thấy tập nào cho phim '$title'. Tạo fallback EpisodeData cho loadLinks.")
                     val moviePageDataIdAttempt = infoDoc.selectFirst("a.watch_button_more[href*=xem-phim]")?.attr("href")
                                ?.substringAfterLast("a")?.substringBefore("/")
                                ?: infoUrl.substringAfterLast("a").substringBefore("/")
                    Log.d("AnimeVietsubProvider", "DataId thử nghiệm cho phim (không có tập): $moviePageDataIdAttempt")
                    gson.toJson(EpisodeData(url = infoUrl, dataId = moviePageDataIdAttempt, duHash = null))
                }


                provider.newMovieLoadResponse(title, infoUrl, TvType.Movie, movieDataForLoadLinks) {
                    this.posterUrl = posterUrl
                    this.plot = description
                    this.tags = genres
                    this.year = year
                    this.rating = rating
                    durationMinutes?.let { addDuration(it.toString()) }
                    addActors(actors)
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong toLoadResponse xử lý cho url: $infoUrl", e); return null
        }
    }


    // Data class để parse response từ ajax/player (cho loadLinks)
    private data class AjaxPlayerResponse(
        @JsonProperty("success") val success: Int? = null,
        @JsonProperty("link") val link: List<LinkSource>? = null, // Đôi khi có thể là String, cần kiểm tra kỹ
        // Thêm các trường khác nếu API trả về (ví dụ: subtitles)
        // @JsonProperty("subtitle") val subtitle: List<SubtitleSource>? = null 
    )
    private data class LinkSource(
        @JsonProperty("file") val file: String? = null, // Chứa "dataenc" hoặc link trực tiếp
        @JsonProperty("type") val type: String? = null, // m3u8, mp4
        @JsonProperty("label") val label: String? = null // 720p, 1080p
    )
    // private data class SubtitleSource(
    //     @JsonProperty("file") val file: String? = null,
    //     @JsonProperty("label") val label: String? = null,
    //     @JsonProperty("kind") val kind: String? = "captions" // "captions" or "subtitles"
    // )


    // === Lấy link xem (Sử dụng API mới, gọi Constructor ExtractorLink, fix type inference) ===
    override suspend fun loadLinks(
        data: String, // Đây là JSON string của EpisodeData
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val baseUrl = getBaseUrl() // Lấy base URL đã kiểm tra
        val ajaxUrl = "$baseUrl/ajax/player?v=2019a" // URL ajax của AnimeVietsub
        val decryptApiUrl = "https://m3u8.013666.xyz/animevietsub/decrypt" // API giải mã (có thể thay đổi)
        val textPlainMediaType = "text/plain".toMediaTypeOrNull()

        Log.d("AnimeVietsubProvider", "LoadLinks nhận được data: $data")

        val episodeData = try {
            gson.fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể parse EpisodeData JSON trong loadLinks: '$data'", e)
            return false // Không thể tiếp tục nếu không parse được data
        }

        val episodePageUrl = episodeData.url // URL của trang tập phim, dùng làm referer
        val episodeId = episodeData.dataId    // data-id của tập phim
        val episodeHash = episodeData.duHash  // data-hash (nếu có)

        // episodeId là bắt buộc cho API ajax
        if (episodeId == null || episodePageUrl.isBlank()) {
            Log.e("AnimeVietsubProvider", "Thiếu ID tập phim (dataId) hoặc URL trang tập phim trong episode data: $data. Không thể tiếp tục.")
            return false
        }

        Log.i("AnimeVietsubProvider", "Đang xử lý ID tập phim: $episodeId bằng phương thức API cho URL: $episodePageUrl")

        try {
            // --- Bước 1: Gửi POST đến ajax/player của AnimeVietsub ---
            // Payload cho request POST
            val postData = mutableMapOf(
                "id" to episodeId,
                "play" to "api" // Yêu cầu API trả về link thay vì player
            )
            if (!episodeHash.isNullOrBlank()) {
                postData["link"] = episodeHash // Thêm hash nếu có
            }
            // postData["backuplinks"] = "1" // Có thể thử thêm để lấy link backup

            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01", // Yêu cầu JSON
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to USER_AGENT,
                "Referer" to episodePageUrl // Referer là trang tập phim
            )

            Log.d("AnimeVietsubProvider", "POSTing đến AnimeVietsub AJAX API: $ajaxUrl với data: $postData")
            val ajaxResponse = app.post(ajaxUrl, data = postData, headers = headers, referer = episodePageUrl)
            Log.d("AnimeVietsubProvider", "AnimeVietsub AJAX API Response Status: ${ajaxResponse.code}")
            // Log.v("AnimeVietsubProvider", "AnimeVietsub AJAX API Response Body: ${ajaxResponse.text}") // Log nếu cần debug

            // --- Bước 2: Parse response JSON từ ajax/player ---
            val playerResponse = try {
                gson.fromJson(ajaxResponse.text, AjaxPlayerResponse::class.java)
            } catch (e: Exception) {
                Log.e("AnimeVietsubProvider", "Không thể parse JSON từ ajax/player: ${ajaxResponse.text}", e); null
            }

            if (playerResponse?.success != 1 || playerResponse.link.isNullOrEmpty()) {
                Log.e("AnimeVietsubProvider", "Request ajax/player thất bại hoặc response không hợp lệ: ${ajaxResponse.text}")
                // Thử fallback nếu có (hiện tại chưa implement)
                return false
            }
            
            // Duyệt qua các link source từ API
            playerResponse.link.forEach { linkSource ->
                val dataEncOrDirectLink = linkSource.file
                if (dataEncOrDirectLink.isNullOrBlank()) {
                    Log.w("AnimeVietsubProvider", "Bỏ qua link source vì 'file' rỗng hoặc null.")
                    return@forEach // Tiếp tục với link source tiếp theo
                }

                val finalM3u8Url: String
                if (dataEncOrDirectLink.startsWith("http") && (dataEncOrDirectLink.contains(".m3u8") || dataEncOrDirectLink.contains(".mp4"))) {
                    // Nếu là link trực tiếp (ít khả năng với AnimeVietsub hiện tại)
                    finalM3u8Url = dataEncOrDirectLink
                    Log.i("AnimeVietsubProvider", "Lấy được link trực tiếp từ API: $finalM3u8Url")
                } else {
                    // Nếu là dataEnc, cần giải mã
                    Log.d("AnimeVietsubProvider", "Lấy được 'dataenc': ${dataEncOrDirectLink.take(50)}...")
                    Log.d("AnimeVietsubProvider", "POSTing 'dataenc' đến API giải mã: $decryptApiUrl")
                    
                    // Request body là dataEnc dạng text/plain
                    val requestBody = dataEncOrDirectLink.toRequestBody(textPlainMediaType)
                    val decryptResponse = app.post(
                        decryptApiUrl,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to episodePageUrl), // Referer có thể là ajaxUrl hoặc episodePageUrl
                        requestBody = requestBody
                    )
                    Log.d("AnimeVietsubProvider", "API giải mã Response Status: ${decryptResponse.code}")
                    // Log.v("AnimeVietsubProvider", "API giải mã Response Body: ${decryptResponse.text}")

                    finalM3u8Url = decryptResponse.text.trim()
                }

                if (finalM3u8Url.startsWith("http") && (finalM3u8Url.endsWith(".m3u8") || finalM3u8Url.contains(".mp4")) ) {
                    Log.i("AnimeVietsubProvider", "Đã lấy thành công link M3U8/MP4 cuối cùng: $finalM3u8Url")

                    val quality = Qualities.Unknown.value // Mặc định, có thể parse từ label nếu có
                    // val qualityFromName = getQualityFromName(linkSource.label) // Hàm helper để parse chất lượng từ tên

                    // Tạo Map chứa các header cần thiết cho trình phát
                    val requiredHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Origin" to baseUrl, // Origin là baseUrl đã lấy được
                        "Referer" to episodePageUrl // Referer nên là trang chứa player hoặc ajaxUrl
                    )

                    callback(
                        ExtractorLink(
                            source = name, // Tên provider
                            name = "AnimeVietsub API" + (linkSource.label?.let { " - $it" } ?: ""), // Tên server + label (nếu có)
                            url = finalM3u8Url,
                            referer = episodePageUrl, // Referer quan trọng
                            quality = Qualities.Unknown.value, //Parse chất lượng từ label nếu có format chuẩn
                            type = if (finalM3u8Url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4,
                            headers = requiredHeaders, // Thêm headers vào đây
                            is आमच्याकडे = false // "is हमारे पास" :D, đây là is आमच्याकडे - is "हमारे पास" (is "from us" / direct link)
                        )
                    )
                    foundLinks = true
                } else {
                    Log.e("AnimeVietsubProvider", "API giải mã không trả về URL M3U8/MP4 hợp lệ. Response: $finalM3u8Url")
                }
            } // Hết vòng lặp duyệt link source

        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi trong quá trình trích xuất link API cho ID tập phim $episodeId", e)
            // Có thể ném lỗi ở đây để thông báo rõ hơn, ví dụ:
            // throw ErrorLoadingException("Không thể tải link phim: ${e.message}")
        }

        if (!foundLinks) {
            Log.w("AnimeVietsubProvider", "Không có link stream nào được trích xuất thành công cho ID tập phim $episodeId ($episodePageUrl)")
            // Có thể thử thêm logic fallback ở đây nếu phương thức API thất bại hoàn toàn
            // Ví dụ: thử parse embed player từ trang web (nếu có)
            // loadExtractor(embedUrl, subtitleCallback, callback)
        }
        return foundLinks // Trả về true nếu ít nhất một link được tìm thấy
    }


    // === Các hàm hỗ trợ ===

    // Mã hóa URL component (RFC 3986)
    private fun String?.encodeUri(): String {
        if (this == null) return ""
        return try {
            // Sử dụng URLEncoder.encode nhưng thay thế '+' bằng '%20' để phù hợp với application/x-www-form-urlencoded
            URLEncoder.encode(this, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Không thể URL encode: $this", e)
            this // Trả về chuỗi gốc nếu lỗi
        }
    }

    // Chuyển đổi điểm đánh giá (thường là 0-10 hoặc 0-5) sang thang 1000 của Cloudstream
    private fun Double?.toAnimeVietsubRatingInt(): Int? {
        // Giả sử rating của AnimeVietsub là thang 10 (ví dụ: 9.4)
        return this?.let { (it * 100).roundToInt().coerceIn(0, 1000) }
    }

    // Sửa lỗi URL tương đối hoặc thiếu scheme (http/https)
    private fun fixUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        return try {
            when {
                url.startsWith("http") -> url // URL đã hoàn chỉnh
                url.startsWith("//") -> "https:$url" // Thiếu scheme, thêm https
                url.startsWith("/") -> URL(URL(baseUrl), url).toString() // URL tương đối từ root
                else -> URL(URL(baseUrl), "/$url".removePrefix("//")).toString() // URL tương đối từ thư mục hiện tại (thêm / nếu cần)
            }
        } catch (e: java.net.MalformedURLException) { // Xử lý lỗi URL không hợp lệ
            Log.e("AnimeVietsubProvider", "URL không hợp lệ khi fix: base='$baseUrl', url='$url'", e)
            if (url.startsWith("http")) return url // Nếu lỗi nhưng đã là http, thử trả về
            null
        } catch (e: Exception) {
            Log.e("AnimeVietsubProvider", "Lỗi không xác định khi fix URL: base='$baseUrl', url='$url'", e)
            null
        }
    }

} // <--- Dấu ngoặc nhọn ĐÓNG class AnimeVietsubProvider
