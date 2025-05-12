package com.lagradost.cloudstream3.plugins // <= THÊM PACKAGE NAME Ở ĐÂY

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
// import android.content.Context // Bỏ import này nếu không dùng getHtmlFromFile với context.assets

class Xvv1deosProvider : MainAPI() {
    override var mainUrl = "https://www.xvv1deos.com"
    override var name = "Xvv1deos"
    override val hasMainPage = true
    override var lang = "en" // Bạn có thể thay đổi nếu trang web có hỗ trợ đa ngôn ngữ
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // === PHẦN ĐỌC FILE HTML CỤC BỘ (CHỈ DÙNG ĐỂ TEST VỚI FILE HTML TĨNH) ===
    // Trong plugin CloudStream thực tế, bạn sẽ KHÔNG dùng hàm này.
    // Thay vào đó, bạn sẽ dùng: val document = app.get(url).document
    // Để hàm này hoạt động khi test cục bộ bên ngoài Cloudstream,
    // bạn cần cung cấp một Context và đặt file HTML vào thư mục assets.
    // Tuy nhiên, cách tốt nhất là dùng app.get() ngay từ đầu khi phát triển trong môi trường Cloudstream.
    /*
    private fun getHtmlFromFile(fileName: String, context: Context? = null): String {
        // Nếu bạn muốn test với file HTML cục bộ khi không có app context (ví dụ: trong unit test đơn giản)
        // bạn có thể đọc file từ đường dẫn cố định.
        // Tuy nhiên, với Cloudstream, bạn sẽ dùng app.get(url).text
        // return java.io.File("path/to/your/html/files/$fileName").readText()

        // Code dưới đây dùng context.assets, chỉ hoạt động nếu bạn chạy trong môi trường Android
        // và đã đặt file vào thư mục assets.
        if (context != null) {
            return context.assets.open(fileName).bufferedReader().use { it.readText() }
        }
        // Đây là phần giả định, bạn cần thay thế bằng logic đọc file thực tế nếu test cục bộ
        // hoặc tốt nhất là thay bằng app.get(fullUrl).text khi triển khai vào Cloudstream
        // Hiện tại, vì không có context, hàm này sẽ trả về chuỗi rỗng nếu không điều chỉnh.
        // ĐỂ TEST TRỰC TIẾP TRONG CLOUDSTREAM, HÃY XÓA/CHÚ THÍCH HÀM NÀY VÀ DÙNG app.get()
        println("CẢNH BÁO: Hàm getHtmlFromFile đang được sử dụng. Đây là code giả lập để đọc file tĩnh.")
        println("Trong plugin thực tế, hãy dùng app.get(url).document.")

        // Giả lập nội dung HTML dựa trên tên file cho mục đích demo nếu không có file thực
        return when (fileName) {
            "home.html" -> "<html><head><title>Home Page</title></head><body><div class='thumb-block'><p class='title'><a href='/video1' title='Video 1'>Video 1 <span class='duration'>10 min</span></a></p><div class='thumb'><a href='/video1'><img data-src='image1.jpg'/></a></div></div></body></html>"
            "search.html" -> "<html><head><title>Search Page</title></head><body><div class='thumb-block'><p class='title'><a href='/video_search' title='Search Result Video'>Search Result <span class='duration'>5 min</span></a></p><div class='thumb'><a href='/video_search'><img data-src='search_image.jpg'/></a></div></div></body></html>"
            "watch.html" -> "<html><head><title>Watch Page Video Title</title><meta property='og:image' content='poster.jpg'><meta name='description' content='Video description.'></head><body><h2 class='page-title'>Watch Page Video Title <span class='duration'>12 min</span></h2><div class='video-tags-list'><ul><li><a class='is-keyword'>tag1</a></li></ul></div><script>var video_related=[];</script></body></html>"
            else -> ""
        }
    }
    */

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("p.title a")
        val title = titleElement?.attr("title")?.ifBlank { titleElement.text() } ?: titleElement?.text() ?: return null
        val href = titleElement.attr("href") ?: return null
        // Đảm bảo href bắt đầu bằng / hoặc là URL tuyệt đối
        val fullUrl = if (href.startsWith("http")) href else mainUrl + href


        val imageElement = this.selectFirst("div.thumb a img")
        var image = imageElement?.attr("data-src")
        if (image.isNullOrBlank() || image.contains("lightbox/lightbox-blank.gif")) {
            image = imageElement?.attr("src") // Fallback to src
        }
        if (image.isNullOrBlank() || image.contains("lightbox/lightbox-blank.gif")){
            image = null // No valid image
        }


        // val durationString = this.selectFirst("span.duration")?.text() // Lấy duration nếu cần ở đây
        // val quality = this.selectFirst("span.video-hd-mark, span.video-sd-mark")?.text()

        return newAnimeSearchResponse(title, fullUrl, TvType.Movie) { // Sử dụng TvType.Movie nếu không phân biệt rõ ràng
            this.posterUrl = image
            // this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageNumber = if (page > 1) "/new/${page - 1}" else "" // Trang chủ thường là / hoặc /new/1 (tương ứng page 2 trong app)
        val document = app.get("$mainUrl$pageNumber").document
        // val htmlContent = getHtmlFromFile("home.html") // Thay thế bằng app.get()
        // val document = Jsoup.parse(htmlContent)

        val items = document.select("div.mozaique div.thumb-block")?.mapNotNull { // Selector chính xác hơn cho home
            it.toSearchResponse()
        }

        return HomePageResponse(listOf(HomePageList("Videos", items ?: emptyList())))
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?k=$query").document
        // val htmlContent = getHtmlFromFile("search.html") // Thay thế bằng app.get()
        // val document = Jsoup.parse(htmlContent)

        return document.select("div.mozaique div.thumb-block")?.mapNotNull { // Selector chính xác hơn cho search
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        // val htmlContent = getHtmlFromFile("watch.html") // Thay thế bằng app.get()
        // val document = Jsoup.parse(htmlContent)

        val title = document.selectFirst("h2.page-title")?.ownText()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        var description = document.selectFirst("meta[name=description]")?.attr("content")
        if (description.isNullOrBlank()) {
            // Thử lấy từ nội dung khác nếu meta description trống hoặc quá chung chung
            description = document.selectFirst("div.video-description-text")?.text() // Giả sử có div này
        }


        var tags: List<String>? = null
        // Ưu tiên lấy tags từ script xv.conf.dyn.video_tags nếu có và đáng tin cậy
        val scriptTagWithConfig = document.select("script").find { it.html().contains("xv.conf") }?.html()
        if (scriptTagWithConfig != null) {
            val tagsRegex = Regex("""video_tags"\s*:\s*(\[.*?\])""")
            val tagsMatch = tagsRegex.find(scriptTagWithConfig)
            if (tagsMatch != null && tagsMatch.groupValues.size > 1) {
                try {
                    val tagsJsonArrayString = tagsMatch.groupValues[1]
                    tags = tagsJsonArrayString.removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotBlank() }
                } catch (e: Exception) {
                    // Lỗi parse JSON, thử cách khác
                }
            }
        }
        // Nếu không lấy được từ script, thử lấy từ HTML elements
        if (tags.isNullOrEmpty()) {
            tags = document.select("div.video-tags-list li a.is-keyword")?.map { it.text() }?.filter { it.isNotBlank() }
        }


        val uploaderName = document.selectFirst("div.video-tags-list li.main-uploader a.uploader-tag span.name")?.text()
        val durationString = document.selectFirst("h2.page-title span.duration")?.text()
        val durationMinutes = durationString?.let { parseDuration(it) }

        val recommendations = mutableListOf<SearchResponse>()
        // Phân tích script chứa video_related
        val scriptContent = document.select("script").find { it.html().contains("var video_related") }?.html()
        if (scriptContent != null) {
            val videoRelatedRegex = Regex("""var video_related=\[(.*?)\];""")
            val matchResult = videoRelatedRegex.find(scriptContent)

            if (matchResult != null) {
                val jsonArrayString = matchResult.groupValues[1]
                val itemRegex = Regex("""\{"id":\s*\d+.*?,"u":"(.*?)","i":"(.*?)",.*?tf":"(.*?)",.*?d":"(.*?)"(?:.*?)\}""")
                itemRegex.findAll(jsonArrayString).forEach { itemMatch ->
                    try {
                        var recTitle = itemMatch.groupValues[3].replace("\\/", "/") // tf
                        recTitle = unescapeUnicode(recTitle)
                        var recHref = itemMatch.groupValues[1].replace("\\/", "/") // u
                        var recImage = itemMatch.groupValues[2].replace("\\/", "/") // i
                        // val recDuration = itemMatch.groupValues[4] // d (có thể parse sau)

                        if (recTitle.isNotBlank() && recHref.isNotBlank()) {
                             if (!recHref.startsWith("http")) recHref = mainUrl + recHref
                             if (!recImage.startsWith("http")) recImage = mainUrl + recImage // Giả sử image link có thể là tương đối
                            recommendations.add(newAnimeSearchResponse(recTitle, recHref, TvType.Movie) {
                                this.posterUrl = recImage
                            })
                        }
                    } catch (e: Exception) {
                        // Bỏ qua item nếu lỗi parse
                    }
                }
            }
        }


        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            this.duration = durationMinutes
            uploaderName?.let { addActors(it) }
        }
    }
    
    private fun parseDuration(durationString: String): Int? {
        var totalMinutes = 0
        val hourMatch = Regex("""(\d+)\s*h""").find(durationString)
        hourMatch?.let {
            totalMinutes += it.groupValues[1].toIntOrNull()?.times(60) ?: 0
        }
        val minMatch = Regex("""(\d+)\s*min""").find(durationString)
        minMatch?.let {
            totalMinutes += it.groupValues[1].toIntOrNull() ?: 0
        }
        val secMatch = Regex("""(\d+)\s*sec""").find(durationString)
        if (totalMinutes == 0 && secMatch != null) {
            if ((secMatch.groupValues[1].toIntOrNull() ?: 0) > 0) {
                totalMinutes = 1 // Làm tròn lên 1 phút nếu chỉ có giây
            }
        }
        return if (totalMinutes > 0) totalMinutes else null
    }
    
    private fun unescapeUnicode(str: String): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(str) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
    }

    override suspend fun loadLinks(
        data: String, // data ở đây là URL của trang xem phim
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // val document = app.get(data).document
        // val scriptContents = document.select("script")

        // var videoUrlHigh: String? = null
        // var videoUrlLow: String? = null
        // var videoHls: String? = null

        // scriptContents.forEach { script ->
        //     val html = script.html()
        //     if (html.contains("html5player.setVideoUrlHigh")) {
        //         videoUrlHigh = Regex("""html5player\.setVideoUrlHigh\('(.*?)'\)""").find(html)?.groupValues?.get(1)
        //     }
        //     if (html.contains("html5player.setVideoUrlLow")) {
        //         videoUrlLow = Regex("""html5player\.setVideoUrlLow\('(.*?)'\)""").find(html)?.groupValues?.get(1)
        //     }
        //     if (html.contains("html5player.setVideoHLS")) {
        //         videoHls = Regex("""html5player\.setVideoHLS\('(.*?)'\)""").find(html)?.groupValues?.get(1)
        //     }
        // }

        // videoHls?.let {
        //     callback.invoke(
        //         ExtractorLink(
        //             source = this.name,
        //             name = "${this.name} HLS",
        //             url = it,
        //             referer = data,
        //             quality = Qualities.Unknown.value, // Chất lượng có thể được xác định từ tên file trong M3U8
        //             isM3u8 = true
        //         )
        //     )
        // }

        // videoUrlHigh?.let {
        //     callback.invoke(
        //         ExtractorLink(
        //             source = this.name,
        //             name = "${this.name} High", // Hoặc có thể lấy chất lượng từ tên file/URL nếu có
        //             url = it,
        //             referer = data,
        //             quality = Qualities.P1080.value // Giả định
        //         )
        //     )
        // }
        // videoUrlLow?.let {
        //    callback.invoke(
        //         ExtractorLink(
        //             source = this.name,
        //             name = "${this.name} Low",
        //             url = it,
        //             referer = data,
        //             quality = Qualities.P360.value // Giả định
        //         )
        //     )
        // }
        
        // return videoHls != null || videoUrlHigh != null || videoUrlLow != null
        println("Hàm loadLinks chưa được triển khai đầy đủ. Bạn cần phân tích để lấy link video trực tiếp.")
        return false
    }
}
