package com.anhdaden

import com.fasterxml.jackson.annotation.JsonProperty

data class BackupVars(
    @JsonProperty("_Bool") val bool: Map<String, Boolean>?,
    @JsonProperty("_Int") val int: Map<String, Int>?,
    @JsonProperty("_String") val string: Map<String, String>?,
    @JsonProperty("_Float") val float: Map<String, Float>?,
    @JsonProperty("_Long") val long: Map<String, Long>?,
    @JsonProperty("_StringSet") val stringSet: Map<String, Set<String>?>?,
)

data class BackupFile(
    @JsonProperty("datastore") val datastore: BackupVars,
    @JsonProperty("settings") val settings: BackupVars
)

data class APIRes(
    @JsonProperty("data") var data: Data?,
    @JsonProperty("errors") var errors: Array<Error>?,
) {
    data class Data(
        @JsonProperty("viewer") var viewer: Viewer?,
        @JsonProperty("addProjectV2DraftIssue") var issue: Issue?,
        @JsonProperty("deleteProjectV2Item") var delItem: DelItem?
    ) {
        data class Viewer(@JsonProperty("projectV2") var projectV2: ProjectV2) {
            data class ProjectV2(
                @JsonProperty("id") var id: String,
                @JsonProperty("items") var items: Items?
            ) {
                data class Items(
                    @JsonProperty("nodes") var nodes: Array<Node>?,
                ) {
                    data class Node(
                        @JsonProperty("id") var id: String,
                        @JsonProperty("content") var content: Content
                    ) {
                        data class Content(
                            @JsonProperty("id") var id: String,
                            @JsonProperty("title") var title: String,
                            @JsonProperty("bodyText") var bodyText: String,
                        )
                    }
                }
            }
        }
        data class Issue(@JsonProperty("projectItem") var projectItem: ProjectItem) {
            data class ProjectItem(
                @JsonProperty("id") var id: String,
                @JsonProperty("content") var content: Content
            ) {
                data class Content(
                    @JsonProperty("id") var id: String
                )
            }
        }
        data class DelItem(@JsonProperty("deletedItemId") var deletedItemId: String)
    }

    data class Error(
        @JsonProperty("message") var message: String?
    )
}

data class SyncDevice(
    @JsonProperty("name") var name: String,
    @JsonProperty("deviceId") var deviceId: String, // draftIssueID // for add update
    @JsonProperty("itemId") var itemId: String, // projectItemID // for delete
    @JsonProperty("syncedData") var syncedData: String? = null
)