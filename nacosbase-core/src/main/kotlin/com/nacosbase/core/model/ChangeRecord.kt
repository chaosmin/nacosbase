package com.nacosbase.core.model

import java.time.Instant

data class ChangeRecord(
    val id: Long,            // DB auto-increment; used for ordering in rollback
    val scriptName: String,
    val checksum: String,
    val appliedAt: Instant,
    val appliedBy: String,
    val executionMs: Long,
    val status: ExecutionStatus,
    val rollbackData: String?,  // JSON array of pre-change NacosConfig snapshots
)
