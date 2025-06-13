package com.lagradost.cloudstream3.hentai.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
// import com.lagradost.cloudstream3.plugins.PluginManager.* // Import này có thể cần thiết

@CloudstreamPlugin
class TestPlugin: Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider để duyệt và tìm kiếm
        registerMainAPI(TestProvider()) // hoặc IHentaiProvider() nếu bạn dùng tên đó

        // *** Đăng ký Extractor để xử lý link ảo .local ***
        registerExtractorAPI(IHentaiExtractor())
    }
}
