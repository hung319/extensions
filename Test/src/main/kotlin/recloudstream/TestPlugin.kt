package recloudstream 

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.CommonActivity.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

@CloudstreamPlugin
class YuuPlugin : Plugin() {
    override fun load(context: Context) {
        val expectedPackage = "com.lagradost.cloudstream3"
        val expectedAppName = "CloudStream"

        // 🔍 Lấy package hiện tại
        val currentPackage = context.packageName

        // 🔍 Lấy tên app đang chạy (dạng hiển thị)
        val currentAppName = context.applicationInfo.loadLabel(context.packageManager).toString()

        // 📛 In log nếu muốn debug
        println("🔎 Đang chạy trên package: $currentPackage - app name: $currentAppName")

        // ❌ Check nếu sai package hoặc sai tên app
        if (currentPackage != expectedPackage || !currentAppName.contains(expectedAppName, ignoreCase = true)) {
            // Hiện cảnh báo toast
            Toast.makeText(
                context,
                "❌ Plugin từ Yuu Onii-chan từ chối chạy trên app lạ!",
                Toast.LENGTH_LONG
            ).show()

            // Dừng plugin không cho load
            throw Error("Ứng dụng không hợp lệ: $currentPackage - $currentAppName")
        }

        // ✅ Nếu hợp lệ, đăng ký provider như thường
        registerMainAPI(WowXXXProvider())

        // 🔔 Gửi toast xác nhận
        withContext(Dispatchers.Main) {
            CommonActivity.activity?.let { activity ->
                showToast(activity, "Free Repo From SIX [H4RS]\nTelegram/Discord: hung319", Toast.LENGTH_LONG)
            }
        }
    }
}
