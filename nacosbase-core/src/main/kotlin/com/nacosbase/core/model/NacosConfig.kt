package com.nacosbase.core.model

data class NacosConfig(
    val dataId: String,
    val group: String,
    val namespace: String,
    val content: String,
    val type: ConfigType,
)
