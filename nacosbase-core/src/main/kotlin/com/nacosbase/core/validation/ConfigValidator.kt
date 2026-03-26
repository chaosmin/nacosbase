package com.nacosbase.core.validation

import com.nacosbase.core.model.ConfigType
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.Yaml

object ConfigValidator {

    private val strictJson = Json { isLenient = false }

    fun validate(content: String, type: ConfigType): Result<Unit> = runCatching {
        when (type) {
            ConfigType.YAML       -> validateYaml(content)
            ConfigType.PROPERTIES -> validateProperties(content)
            ConfigType.JSON       -> validateJson(content)
            ConfigType.TEXT       -> Unit
        }
    }

    private fun validateYaml(content: String) {
        Yaml().load<Any>(content)
    }

    private fun validateProperties(content: String) {
        val lines = content.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
        for (line in lines) {
            require(line.contains('=') && line.indexOf('=') > 0) {
                "Invalid properties line (missing key): $line"
            }
        }
    }

    private fun validateJson(content: String) {
        strictJson.parseToJsonElement(content)
    }
}
