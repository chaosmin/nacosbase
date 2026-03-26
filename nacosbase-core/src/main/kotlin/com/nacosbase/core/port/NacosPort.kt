package com.nacosbase.core.port

import com.nacosbase.core.model.NacosConfig

interface NacosPort {
    fun fetchAll(namespace: String): List<NacosConfig>
    fun publish(config: NacosConfig)
    fun delete(dataId: String, group: String, namespace: String)
    fun namespaceExists(namespace: String): Boolean
}
