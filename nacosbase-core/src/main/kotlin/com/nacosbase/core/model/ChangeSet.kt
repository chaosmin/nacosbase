package com.nacosbase.core.model

data class ChangeSet(
    val action: Action,
    val dataId: String,
    val group: String,
    val namespace: String,
    val content: String?,     // null for DELETE
    val type: ConfigType?,
    val description: String?, // per-row description for audit trail
)
