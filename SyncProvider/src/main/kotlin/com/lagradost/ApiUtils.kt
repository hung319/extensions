package com.anhdaden

import android.util.Base64
import android.content.Context
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.fasterxml.jackson.module.kotlin.readValue

object ApiUtils {
    private fun Any.toStringData(): String {
        return mapper.writeValueAsString(this)
    }

    private suspend fun apiCall(query: String): APIRes? {
        try {
            val token = getKey<String>("sync_token")
            val apiUrl = "https://api.github.com/graphql"
            val header = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token"
            )
            val data = """ { "query": ${query} } """
            val res = app.post(apiUrl, headers = header, json = data)

            return res.parsedSafe<APIRes>()
        } catch (e: Exception) {
            return null
        }
    }

    fun isLoggedIn(): Boolean {
        val token = getKey<String>("sync_token")
        val projectNum = getKey<String>("sync_project_num")
        val projectId = getKey<String>("sync_project_id")

        return !(token.isNullOrEmpty() || projectNum.isNullOrEmpty() || projectId.isNullOrEmpty())
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String?> {
        var failure = false to "Project not found"
        var failureToken = false to "Github token is wrong"
        val projectNum = getKey<String>("sync_project_num")
        val query = """ query Viewer { viewer { projectV2(number: ${projectNum}) { id } } } """
        val res = apiCall(query.toStringData()) ?: return failureToken
        val projectId = res.data?.viewer?.projectV2?.id ?: return failure
        setKey("sync_project_id", projectId)
        val devices: List<SyncDevice>? = fetchDevices()
        if (devices?.isEmpty() == false && devices?.size ?: 0 > 0) {
            val firstDevice: SyncDevice = devices!!.first()
            setKey("sync_item_id", firstDevice.itemId ?: "")
            setKey("sync_device_id", firstDevice.deviceId ?: "")
            if (getKey<String>("restore_device") == "true") {
                val restoredValue = mapper.readValue<BackupFile>(firstDevice.syncedData ?: "")
                BackupUtils.restore(context, restoredValue, true, true)
            }
        } else if (getKey<String>("backup_device") == "true") {
            val syncData = BackupUtils.getBackup(context, getResumeWatching())?.toJson() ?: ""
            val data = Base64.encodeToString(syncData.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            val query = """ mutation AddProjectV2DraftIssue { addProjectV2DraftIssue( input: { projectId: "$projectId", title: "${getKey<String>("device_id")}", body: "$data" } ) { projectItem { id content { ... on DraftIssue { id } } } } } """
            val res = apiCall(query.toStringData()) ?: return failureToken
            val itemId = res.data?.issue?.projectItem?.id ?: return false to res.errors?.get(0)?.message?.toString()
            val deviceId = res.data?.issue?.projectItem?.content?.id ?: return false to res.errors?.get(0)?.message?.toString()
            setKey("sync_item_id", itemId)
            setKey("sync_device_id", deviceId)
        } else {
            setKey("sync_token", "")
            setKey("sync_project_num", "")
            return false to "Error finding backup device"
        }
        return true to "Device registered successfully"
    }

    suspend fun syncThisDevice(syncData: String): Pair<Boolean, String?> {
        val failure = false to "Error sync this device id: ${getKey<String>("device_id")}"
        if (!isLoggedIn()) return failure
        val data = Base64.encodeToString(syncData.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val deviceId = getKey<String>("sync_device_id")
        val query = """ mutation UpdateProjectV2DraftIssue { updateProjectV2DraftIssue( input: { draftIssueId: "$deviceId", title: "${getKey<String>("device_id")}", body: "$data" } ) { draftIssue { id } } } """
        apiCall(query.toStringData()) ?: return failure
        
        return true to "Sync success"
    }

    suspend fun fetchDevices(): List<SyncDevice>? {
        if (!isLoggedIn()) return null
        val projectNum = getKey<String>("sync_project_num")
        val query = """ query User { viewer { projectV2(number: ${projectNum}) { id items(first: 50) { nodes { id content { ... on DraftIssue { id title bodyText } } } totalCount } } } } """
        val res = apiCall(query.toStringData())
        return res?.data?.viewer?.projectV2?.items?.nodes?.map {
            val data = Base64.decode(it.content.bodyText, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
            SyncDevice(it.content.title, it.content.id, it.id, data)
        }
    }
}
