package com.nacosbase.infra.nacos

import com.alibaba.nacos.api.NacosFactory
import com.alibaba.nacos.api.config.ConfigService
import com.nacosbase.core.model.ConfigType
import com.nacosbase.core.model.NacosConfig
import com.nacosbase.core.port.NacosPort
import com.nacosbase.infra.config.NacosConfig as InfraConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

class NacosClientAdapter(private val config: InfraConfig) : NacosPort {

    private val configServiceCache = ConcurrentHashMap<String, ConfigService>()

    override fun fetchAll(namespace: String): List<NacosConfig> {
        val allItems = mutableListOf<NacosConfig>()
        var pageNo = 1
        val pageSize = 200
        while (true) {
            val url = "http://${config.serverAddr}/nacos/v1/cs/configs?" +
                "search=accurate&dataId=&group=&tenant=$namespace&pageSize=$pageSize&pageNo=$pageNo"
            val json = httpGet(url)
            val root = JSONObject(json)
            val items = root.getJSONArray("pageItems")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                allItems.add(
                    NacosConfig(
                        dataId = item.getString("dataId"),
                        group = item.getString("group"),
                        namespace = namespace,
                        content = item.optString("content", ""),
                        type = parseType(item.optString("type", "text")),
                    )
                )
            }
            val totalCount = root.getInt("totalCount")
            if (allItems.size >= totalCount) break
            pageNo++
        }
        return allItems
    }

    override fun publish(config: NacosConfig) {
        getConfigService(config.namespace).publishConfig(
            config.dataId,
            config.group,
            config.content,
            mapNacosType(config.type),
        )
    }

    override fun delete(dataId: String, group: String, namespace: String) {
        getConfigService(namespace).removeConfig(dataId, group)
    }

    override fun namespaceExists(namespace: String): Boolean {
        val json = httpGet("http://${config.serverAddr}/nacos/v1/console/namespaces")
        val root = JSONObject(json)
        val data = root.getJSONArray("data")
        for (i in 0 until data.length()) {
            if (data.getJSONObject(i).getString("namespace") == namespace) return true
        }
        return false
    }

    private fun getConfigService(namespace: String): ConfigService =
        configServiceCache.getOrPut(namespace) {
            NacosFactory.createConfigService(
                Properties().apply {
                    setProperty("serverAddr", config.serverAddr)
                    setProperty("username", config.username)
                    setProperty("password", config.password)
                    setProperty("namespace", namespace)
                }
            )
        }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", basicAuth())
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun basicAuth(): String {
        val credentials = "${config.username}:${config.password}"
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
    }

    private fun mapNacosType(type: ConfigType): String = when (type) {
        ConfigType.YAML -> "yaml"
        ConfigType.PROPERTIES -> "properties"
        ConfigType.JSON -> "json"
        ConfigType.TEXT -> "text"
    }

    private fun parseType(nacosType: String): ConfigType = when (nacosType.lowercase()) {
        "yaml", "yml" -> ConfigType.YAML
        "properties" -> ConfigType.PROPERTIES
        "json" -> ConfigType.JSON
        else -> ConfigType.TEXT
    }
}
