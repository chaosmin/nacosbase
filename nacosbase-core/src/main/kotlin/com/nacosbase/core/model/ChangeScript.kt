package com.nacosbase.core.model

data class ChangeScript(
    val scriptName: String,  // e.g. "001-init-redis.csv"
    val checksum: String,    // SHA-256 of file content
    val changeSets: List<ChangeSet>,
)
