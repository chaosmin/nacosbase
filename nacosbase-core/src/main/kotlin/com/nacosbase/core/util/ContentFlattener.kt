package com.nacosbase.core.util

import com.nacosbase.core.model.ConfigType
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringReader
import java.util.Properties

/**
 * Converts between a config's full text content and a flat list of key-value pairs.
 *
 * Used in two directions:
 *  - flatten: config content → key-value rows (for baseline CSV output)
 *  - assemble: key-value rows → config content (for CsvScriptLoader)
 */
object ContentFlattener {

    /** Parse [content] into an ordered list of (key, value) pairs based on [type]. */
    fun flatten(content: String, type: ConfigType): List<Pair<String, String>> = when (type) {
        ConfigType.YAML       -> flattenYaml(content)
        ConfigType.PROPERTIES -> flattenProperties(content)
        ConfigType.JSON       -> flattenJson(content)
        ConfigType.TEXT       -> listOf("" to content)
    }

    /** Rebuild config content from an ordered list of (key, value) pairs based on [type]. */
    fun assemble(entries: List<Pair<String, String>>, type: ConfigType): String = when (type) {
        ConfigType.YAML       -> assembleYaml(entries)
        ConfigType.PROPERTIES -> entries.joinToString("\n") { (k, v) -> if (k.isEmpty()) v else "$k=$v" }
        ConfigType.JSON       -> assembleJson(entries)
        ConfigType.TEXT       -> entries.firstOrNull()?.second ?: ""
    }

    private fun yamlDumperOptions() = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = 2
        indicatorIndent = 2
        indentWithIndicator = true
    }

    private fun assembleYaml(entries: List<Pair<String, String>>): String {
        if (entries.size == 1 && entries[0].first.isEmpty()) return entries[0].second
        val root = toNestedMap(entries)
        return Yaml(yamlDumperOptions()).dump(root).trimEnd('\n')
    }

    private fun assembleJson(entries: List<Pair<String, String>>): String {
        val root = toNestedMap(entries)
        return nestedMapToJson(root).toString()
    }

    private fun toNestedMap(entries: List<Pair<String, String>>): LinkedHashMap<String, Any> {
        val root = LinkedHashMap<String, Any>()
        for ((key, value) in entries) {
            if (key.isEmpty()) continue
            setNestedValue(root, key.split("."), value)
        }
        return root
    }

    @Suppress("UNCHECKED_CAST")
    private fun setNestedValue(map: MutableMap<String, Any>, keys: List<String>, value: String) {
        if (keys.size == 1) {
            map[keys[0]] = tryParseYamlValue(value)
            return
        }
        val child = map.getOrPut(keys[0]) { LinkedHashMap<String, Any>() }
        setNestedValue(child as MutableMap<String, Any>, keys.drop(1), value)
    }

    /** If [value] is a YAML-serialized list (starts with `- `), parse it back to a native List. */
    private fun tryParseYamlValue(value: String): Any {
        val trimmed = value.trimStart()
        if (!trimmed.startsWith("- ") && !trimmed.startsWith("-\n")) return value
        return try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Any>(value) ?: value
        } catch (_: Exception) {
            value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nestedMapToJson(map: Map<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> put(k, nestedMapToJson(v as Map<String, Any>))
                else         -> put(k, v.toString())
            }
        }
    }

    // ── YAML ─────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun flattenYaml(content: String): List<Pair<String, String>> {
        val obj = Yaml().load<Any?>(content) ?: return emptyList()
        return flattenAny(obj, "")
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenAny(obj: Any?, prefix: String): List<Pair<String, String>> = when (obj) {
        is Map<*, *> -> obj.entries
            .sortedBy { it.key.toString() }
            .flatMap { (k, v) ->
                val path = if (prefix.isEmpty()) k.toString() else "$prefix.$k"
                flattenAny(v, path)
            }
        is List<*>   -> listOf(prefix to Yaml(yamlDumperOptions()).dump(obj).trimEnd('\n'))
        null         -> listOf(prefix to "")
        else         -> listOf(prefix to obj.toString())
    }

    // ── PROPERTIES ───────────────────────────────────────────────────────────

    private fun flattenProperties(content: String): List<Pair<String, String>> {
        val props = Properties()
        props.load(StringReader(content))
        return props.stringPropertyNames().sorted().map { it to props.getProperty(it) }
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    private fun flattenJson(content: String): List<Pair<String, String>> =
        flattenJsonElement(Json.parseToJsonElement(content), "")

    private fun flattenJsonElement(element: JsonElement, prefix: String): List<Pair<String, String>> = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.flatMap { (k, v) ->
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            flattenJsonElement(v, path)
        }
        is JsonArray  -> listOf(prefix to element.toString())
        else          -> listOf(prefix to element.jsonPrimitive.content)
    }
}
