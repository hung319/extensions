package com.h4rs

import android.content.Context
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.DataStore.mapper
import com.lagradost.cloudstream3.utils.Coroutines.main
import io.ktor.client.request.*
import io.ktor.client.statement.*
import com.lagradost.cloudstream3.AcraApplication.Companion.app
import com.fasterxml.jackson.module.kotlin.readValue
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.AppUtils.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.toBase64

object ApiUtils {
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    private val failure = Pair(false, "Project not found, please check project number")
    private val failureToken = Pair(false, "Github token is wrong")
    private val headers = mapOf(
        "Accept" to "application/vnd.github.v4.idl",
        "Content-Type" to "application/json",
        "User-Agent" to USER_AGENT,
        "Authorization" to "bearer ${getKey<String>("sync_token")}"
    )

    private suspend fun apiCall(query: String?): APIRes? {
        if (query == null) return null
        try {
            val req = app.post(
                "https://api.github.com/graphql",
                headers = headers,
                json = JSONObject(mapOf("query" to query))
            ).text
            return tryParseJson<APIRes>(req)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun String.toStringData(): String {
        return this.replace("\"", "\\\"").replace("\n", "")
    }

    fun isLoggedIn(): Boolean {
        return !getKey<String>("sync_token").isNullOrEmpty() && !getKey<String>("sync_project_num").isNullOrEmpty()
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String>? {
        if (!isLoggedIn()) return null
        val projectNum = getKey<String>("sync_project_num") ?: return failure
        val query = """ query { viewer { projectV2(number: ${projectNum}) { id } } } """
        val res = apiCall(query.toStringData()) ?: return failureToken
        val projectId = res.data?.viewer?.projectV2?.id ?: return failure
        setKey("sync_project_id", projectId)

        // LOGIC MỚI BẮT ĐẦU TỪ ĐÂY
        val devices: List<SyncDevice>? = fetchDevices()
        val currentDeviceId = getKey<String>("device_id")

        // Tìm xem có bản sao lưu nào khớp với ID của thiết bị hiện tại không
        val existingDevice = devices?.firstOrNull { it.name == currentDeviceId }

        if (existingDevice != null) {
            // Đã có bản sao lưu cho ID này, ta sẽ sử dụng nó
            setKey("sync_item_id", existingDevice.itemId)
            setKey("sync_device_id", existingDevice.deviceId)

            // Tùy chọn: Khôi phục dữ liệu nếu người dùng bật
            if (getKey<String>("restore_device") == "true") {
                val restoredValue = mapper.readValue<BackupFile>(existingDevice.syncedData ?: "")
                BackupUtils.restore(context, restoredValue, true, true)
                return Pair(true, "Restored data from $currentDeviceId")
            }
            return Pair(true, "Synced with existing device: $currentDeviceId")
        } else {
            // Không tìm thấy bản sao lưu cho ID này, tiến hành tạo mới
            if (getKey<String>("backup_device") == "true") {
                val data =
                    BackupUtils.getBackup(context, HomeViewModel.getResumeWatching())?.toJson()
                        ?: ""
                val createQuery =
                    """ mutation CreateDraftIssue { addProjectV2DraftIssue( input: { projectId: "$projectId", title: "$currentDeviceId", body: "${data.toBase64()}" } ) { projectItem { id content { ... on DraftIssue { id } } } } } """
                val createRes =
                    apiCall(createQuery.toStringData()) ?: return failureToken
                val issue = createRes.data?.issue
                if (issue != null) {
                    setKey("sync_item_id", issue.projectItem.id)
                    setKey("sync_device_id", issue.projectItem.content.id)
                    return Pair(true, "Created new backup for device: $currentDeviceId")
                } else {
                    return failure
                }
            } else {
                 return Pair(true, "Login successful. Backup is disabled, no new device created.")
            }
        }
    }

    suspend fun syncThisDevice(data: String): Pair<Boolean, String>? {
        if (!isLoggedIn()) return null
        val deviceId = getKey<String>("sync_device_id") ?: return failure
        val query =
            """ mutation UpdateProjectV2DraftIssue { updateProjectV2DraftIssue( input: { draftIssueId: "$deviceId", title: "${getKey<String>("device_id")}", body: "${data.toBase64()}" } ) { draftIssue { id } } } """
        val res = apiCall(query.toStringData())
        if (res != null) {
            main {
                //showToast("Sync Success")
            }
            return Pair(true, "Sync Success")
        } else {
            main {
                //showToast("Sync Fail")
            }
            return failure
        }
    }

    suspend fun fetchDevices(): List<SyncDevice>? {
        if (!isLoggedIn()) return null
        val projectNum = getKey<String>("sync_project_num") ?: return null
        val query = """
        query{
            viewer {
                projectV2(number: ${projectNum}) {
                    id
                    items(first: 100) {
                        nodes{
                            id
                            content{
                                ...on DraftIssue {
                                    id
                                    title
                                    bodyText
                                }
                            }
                        }
                    }
                }
            }
        }
        """.toStringData()
        val res = apiCall(query) ?: return null
        val nodes = res.data?.viewer?.projectV2?.items?.nodes
        if (nodes != null) {
            return nodes.map {
                SyncDevice(
                    name = it.content.title,
                    deviceId = it.content.id,
                    itemId = it.id,
                    syncedData = it.content.bodyText.base64Decode()
                )
            }
        } else {
            return null
        }
    }

}