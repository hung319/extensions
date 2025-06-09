package recloudstream

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType // Đảm bảo import nếu cần
import okhttp3.FormBody

class MissAVProvider : MainAPI() {
    override var mainUrl          = "https://missav.live"
    override var name             = "MissAV"
    override val hasMainPage      = true
    override var lang             = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes   = setOf(TvType.NSFW)
    override val vpnStatus        = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/dm514/en/new" to "Recent Update",
        "/dm562/en/release" to "New Releases",
        "/dm620/en/uncensored-leak" to "Uncensored Leak",
        "/dm291/en/today-hot" to "Most Viewed Today",
        "/dm169/en/weekly-hot" to "Most Viewed by Week",
        "/dm256/en/monthly-hot" to "Most Viewed by Month"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}?page=$page").document
        val responseList = document.select(".thumbnail").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, responseList, isHorizontalImages = true),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val titleEl = this.selectFirst(".text-secondary") ?: return null
        val status = this.selectFirst(".bg-blue-800")?.text()
        val title = if (!status.isNullOrBlank()) {
            "[$status] ${titleEl.text()}"
        } else {
            titleEl.text()
        }
        val posterUrl = this.selectFirst(".w-full")?.attr("data-src")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) { // Lặp tối đa 5 trang
            val document = app.get("$mainUrl/en/search/$query?page=$i").document
            val results = document.select(".thumbnail").mapNotNull { it.toSearchResult() }
            if (results.isEmpty()) {
                break
            }
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url } // Trả về kết quả không trùng lặp
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.replace("| PornHoarder.tv", "")
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        return newMovieLoadResponse(title ?: "No title", url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        app.get(data).let {
            getAndUnpack(it.text).let { unpackedText ->
                val linkList = unpackedText.split(";")
                val finalLink = "source='(.*)'".toRegex().find(linkList.first())?.groupValues?.get(1)

                if (finalLink != null) {
                    // Cập nhật theo cách mới nhất: sử dụng ExtractorLinkType.M3U8
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = finalLink,
                            type = ExtractorLinkType.M3U8 // Đặt loại link ở đây
                        ) {
                            // Các thuộc tính khác vẫn ở trong initializer
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
        return true
    }
}
