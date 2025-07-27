package recloudstream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApiKt
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.MainAPIKt.base64Decode
import org.jsoup.nodes.Element
import java.util.regex.Pattern

/**
 * Helper class to unpack the popular JS packer format `eval(function(p,a,c,k,e,d))`
 */
class JsUnpacker(private val packedJS: String) {
    fun unpack(): String? {
        val p = packedJS.substringAfter("eval(function(p,a,c,k,e,d){").substringBefore("}))")
        val pattern = Pattern.compile("'.*?'.*?,''.*?'")
        val matcher = pattern.matcher(p)
        if (!matcher.find()) return null

        val all = matcher.group(0)
        val payload = all.substringBeforeLast(",")
        val dictionary = all.substringAfterLast(",").substringAfter("'").substringBefore("'")

        val p2 = payload.substringBeforeLast("'")
        val a = payload.substringAfterLast(",").toIntOrNull() ?: return null
        val c = payload.substringAfter(",").substringBefore(",").toIntOrNull() ?: return null
        val k = dictionary.split('|')
        val e = c
        val d = 0

        var unpacked = p2.replace("'", "")

        fun base(num: Int): String {
            return if (num < a) "" else base(num / a) + when (val rem = num % a) {
                in 0..9 -> rem.toString()
                else -> (rem + 29).toChar()
            }
        }

        for (i in (c - 1) downTo 0) {
            if (k[i].isNotEmpty()) {
                unpacked = unpacked.replace(Regex("\\b${base(i)}\\b"), k[i])
            }
        }
        return unpacked
    }
}


class TvPhimProvider : MainAPI() {
    override var mainUrl = "https://tvphim.bid" // Sẽ được cập nhật động
    override var name = "TvPhim"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Selector đã được tối ưu dựa trên phân tích
    private val movieItemSelector = "div.item.movies"

    // Hàm lấy tên miền động, tương tự logic trong file Java
    private suspend fun getDomain(): String {
        // "aHR0cHM6Ly9iaXQubHkvZmFudHhwaGlt" là base64 của "https://bit.ly/fantxphim"
        val bitlyUrl = base64Decode("aHR0cHM6Ly9iaXQubHkvZmFudHhwaGlt")
        // app.get sẽ tự động theo dõi chuyển hướng để lấy URL cuối cùng
        return app.get(bitlyUrl, allowRedirects = true).url
    }

    override suspend fun onResume() {
        // Cập nhật mainUrl mỗi khi resume
        mainUrl = getDomain()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (mainUrl.isBlank() || !mainUrl.startsWith("http")) {
            mainUrl = getDomain()
        }
        val url = if (page > 1) "$mainUrl/${request.data}/page/$page/" else "$mainUrl/${request.data}"
        val document = app.get(url).document

        val sections = document.select("div.section")
        if (sections.isEmpty()) throw Exception("Không thể tải trang chủ. Có thể do Cloudflare.")

        val homePageList = sections.mapNotNull { section ->
            val title = section.selectFirst("div.section-title")?.text() ?: return@mapNotNull null
            val movies = section.select(movieItemSelector).mapNotNull { it.toSearchResult() }
            if (movies.isNotEmpty()) HomePageList(title, movies) else null
        }

        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title")
        val posterUrl = this.selectFirst("div.poster img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (mainUrl.isBlank() || !mainUrl.startsWith("http")) {
            mainUrl = getDomain()
        }
        val url = "$mainUrl/tim-kiem/$query/"
        val document = app.get(url).document

        return document.select(movieItemSelector).mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: "N/A"
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
        val isTvSeries = document.select("div#list_episodes").isNotEmpty()

        if (isTvSeries) {
            val episodes = document.select("div#list_episodes a").map {
                val epUrl = fixUrl(it.attr("href"))
                val epName = it.text().trim()
                // Sửa lỗi deprecated constructor
                newEpisode(epUrl) { this.name = epName }
            }.reversed()
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Phân tích logic từ file loadlinks.html và TvPhimProvider.java
        val document = app.get(data).document

        // Tìm script có chứa logic giải mã link
        val scripts = document.select("script")
        val packedScript = scripts.find { it.data().contains("eval(function(w,i,s,e)") }?.data()

        if (packedScript != null) {
            // Đây là luồng S.PRO, sử dụng Unpacker để giải mã
            val unpacked = JsUnpacker(packedScript).unpack()
            val sourceUrl = unpacked?.let {
                Regex("""sources:\[\{file:'(.*?)'""").find(it)?.groupValues?.get(1)
            }
            if (sourceUrl != null) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        "S.PRO",
                        sourceUrl,
                        mainUrl,
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }

        // Tìm các server khác như R.PRO
        document.select("a[title*='Server R.PRO']").firstOrNull()?.attr("href")?.let { rProUrl ->
            if (rProUrl.contains("ok.ru")) {
                ExtractorApiKt.loadExtractor(rProUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
