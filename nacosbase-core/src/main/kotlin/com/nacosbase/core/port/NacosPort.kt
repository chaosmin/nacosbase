package com.nacosbase.core.port

import com.nacosbase.core.model.NacosConfig

interface NacosPort {
    fun fetchAll(namespace: String): List<NacosConfig>
    fun publish(config: NacosConfig)
    fun delete(dataId: String, group: String, namespace: String)
    fun namespaceExists(namespace: String): Boolean
    /** Resolves a namespace name (e.g. "dev") or ID to the actual Nacos namespace ID (UUID). Throws if not found. */
    fun resolveNamespaceId(nameOrId: String): String
}
