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
import java.net.URLEncoder
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

class NacosClientAdapter(private val config: InfraConfig) : NacosPort {

    private val configServiceCache = ConcurrentHashMap<String, ConfigService>()

    @Volatile private var cachedToken: String? = null

    // Lazy login: obtains accessToken from Nacos auth endpoint on first use.
    private fun token(): String {
        cachedToken?.let { return it }
        val url = URL("http://${config.serverAddr}/nacos/v1/auth/users/login")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = "username=${URLEncoder.encode(config.username, "UTF-8")}" +
            "&password=${URLEncoder.encode(config.password, "UTF-8")}"
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val response = try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val token = JSONObject(response).optString("accessToken", "")
        require(token.isNotEmpty()) { "Nacos login failed — server response: $response" }
        cachedToken = token
        return token
    }

    override fun fetchAll(namespace: String): List<NacosConfig> {
        val allItems = mutableListOf<NacosConfig>()
        var pageNo = 1
        val pageSize = 200
        val tok = token()
        while (true) {
            val url = "http://${config.serverAddr}/nacos/v1/cs/configs?" +
                "search=accurate&dataId=&group=&tenant=$namespace" +
                "&pageSize=$pageSize&pageNo=$pageNo&accessToken=$tok"
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

    override fun namespaceExists(namespace: String): Boolean =
        runCatching { resolveNamespaceId(namespace) }.isSuccess

    override fun resolveNamespaceId(nameOrId: String): String {
        val json = httpGet("http://${config.serverAddr}/nacos/v1/console/namespaces?accessToken=${token()}")
        val root = JSONObject(json)
        val data = root.getJSONArray("data")
        // First pass: match by display name (namespaceShowName), e.g. "dev"
        for (i in 0 until data.length()) {
            val ns = data.getJSONObject(i)
            if (ns.optString("namespaceShowName") == nameOrId) return ns.getString("namespace")
        }
        // Second pass: match by namespace ID directly (UUID or empty string for public)
        for (i in 0 until data.length()) {
            val ns = data.getJSONObject(i)
            if (ns.getString("namespace") == nameOrId) return ns.getString("namespace")
        }
        val available = (0 until data.length())
            .map { data.getJSONObject(it).optString("namespaceShowName") }
            .filter { it.isNotEmpty() }
        error("Namespace '$nameOrId' not found in Nacos. Available: $available")
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
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
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
