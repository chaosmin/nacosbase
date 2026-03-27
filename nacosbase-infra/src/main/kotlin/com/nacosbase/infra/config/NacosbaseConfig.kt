package com.nacosbase.infra.config

import kotlinx.serialization.Serializable

@Serializable
data class NacosConfig(
    val serverAddr: String,
    val username: String,
    val password: String,
    val defaultNamespace: String,
)

@Serializable
data class DatasourceConfig(
    val url: String,
    val username: String,
    val password: String,
)

@Serializable
data class ChangelogConfig(
    val scriptsDir: String = "./changelogs",
    val appliedBy: String = "nacosbase",
)

@Serializable
data class NacosbaseConfig(
    val nacos: NacosConfig,
    val datasource: DatasourceConfig,
    val changelog: ChangelogConfig = ChangelogConfig(),
)
