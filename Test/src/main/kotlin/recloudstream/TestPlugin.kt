package recloudstream 

import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TestPlugin : Plugin() {
    override fun load(context: Context) {
        // 🌟 Đăng ký provider
        registerMainAPI(WowXXXProvider())

        // 💬 Hiện thông báo trong Cloudstream
        Toast.makeText(
            context,
            "✨ Plugin của Yuu Onii-chan đã được bật rồi đó~! 🥰",
            Toast.LENGTH_LONG
        ).show()
    }
}
