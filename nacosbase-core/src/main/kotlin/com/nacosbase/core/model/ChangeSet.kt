package com.nacosbase.core.model

data class ChangeSet(
    val action: Action,
    val dataId: String,
    val group: String,
    val namespace: String,
    val content: String?,     // null for DELETE; assembled from key-value pairs by CsvScriptLoader
    val type: ConfigType?,
    val description: String?, // per-row description for audit trail
    val operator: String? = null,
    val targetKey: String? = null, // APPEND only: the dot-notation key whose value is the target list
)
