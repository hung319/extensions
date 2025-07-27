package recloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
// Import này có thể cần thiết nếu registerMainAPI là extension function
// import com.lagradost.cloudstream3.plugins.PluginManager.registerMainAPI

@CloudstreamPlugin // Đánh dấu đây là plugin
class TestPlugin: Plugin() { // Kế thừa Plugin
    override fun load(context: Context) {
        val allowedPackages = listOf(
            "com.lagradost.cloudstream3",
            "com.lagradost.cloudstream3.prerelease"
        )
        val expectedAppName = "CloudStream"

        // 📦 Package và tên app hiện tại
        val currentPackage = context.packageName
        val currentAppName = context.applicationInfo.loadLabel(context.packageManager).toString()

        // ❌ Nếu không đúng app hoặc package thì chặn
        if (currentPackage !in allowedPackages || !currentAppName.contains(expectedAppName, ignoreCase = true)) {
            throw Error("⛔ Ứng dụng không hợp lệ! Bị chặn bởi plugin Yuu.")
        }

        // ✅ Nếu hợp lệ, chạy tiếp
        registerMainAPI(TvPhimBidProvider())
    }
}
