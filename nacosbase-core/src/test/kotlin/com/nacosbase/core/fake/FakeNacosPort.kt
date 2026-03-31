package com.nacosbase.core.fake

import com.nacosbase.core.model.NacosConfig
import com.nacosbase.core.port.NacosPort

class FakeNacosPort : NacosPort {
    val configs = mutableMapOf<String, NacosConfig>()  // key = "$namespace/$group/$dataId"
    val namespaces = mutableSetOf<String>()
    var publishError: Exception? = null

    private fun key(dataId: String, group: String, namespace: String) = "$namespace/$group/$dataId"

    override fun fetchAll(namespace: String) =
        configs.values.filter { it.namespace == namespace }

    override fun publish(config: NacosConfig) {
        publishError?.let { throw it }
        configs[key(config.dataId, config.group, config.namespace)] = config
    }

    override fun delete(dataId: String, group: String, namespace: String) {
        configs.remove(key(dataId, group, namespace))
    }

    override fun namespaceExists(namespace: String) = namespace in namespaces

    override fun resolveNamespaceId(nameOrId: String): String {
        require(nameOrId in namespaces) { "namespace '$nameOrId' does not exist in Nacos. Please create it first." }
        return nameOrId
    }
}
