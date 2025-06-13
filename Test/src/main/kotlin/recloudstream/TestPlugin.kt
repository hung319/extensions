package com.lagradost.cloudstream3.hentai.providers

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TestPlugin: Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider để duyệt và tìm kiếm
        registerMainAPI(IHentaiProvider()) // Đảm bảo tên lớp này khớp với file trên

        // Đăng ký Extractor để xử lý link ảo .local
        registerExtractorAPI(IHentaiExtractor())
    }
}
