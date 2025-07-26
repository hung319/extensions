package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Định nghĩa lớp provider, kế thừa từ MainAPI
class WowXXXProvider : MainAPI() {
    // Tên của provider sẽ hiển thị trong ứng dụng
    override var name = "WowXXX"
    // URL chính của trang web
    override var mainUrl = "https://www.wow.xxx"
    // Báo cho app biết provider này có trang chính
    override var hasMainPage = true
    // Ngôn ngữ được hỗ trợ
    override var lang = "en"
    // Các loại nội dung được hỗ trợ
    override val supportedTypes = setOf(
        TvType.NSFW
    )

    // Hàm để lấy danh sách phim cho trang chính
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = document.select("div.list-videos div.item").mapNotNull {
            it.toSearchResult()
        }
        return HomePageResponse(listOf(HomePageList("Latest", home)))
    }

    // Hàm tiện ích để chuyển đổi một phần tử HTML thành đối tượng MovieSearchResponse
    private fun Element.toSearchResult(): MovieSearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("strong.title")?.text() ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // Hàm tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/relevance/"
        val document = app.get(url).document

        return document.select("div.list-videos div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Hàm tải thông tin chi tiết của một phim
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.headline h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("video")?.attr("poster")
        
        val actors = document.select("div.item:contains(Pornstars) a.btn_model").map {
            ActorData(Actor(it.text()))
        }
        
        val tags = document.select("div.item:contains(Categories) a.btn_tag").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.actors = actors
            this.tags = tags
        }
    }

    // Hàm tải các liên kết xem phim
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("video source").forEach {
            val videoUrl = it.attr("src")
            val quality = it.attr("label")
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$name $quality",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = getQualityFromName(quality),
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        return true
    }
}
