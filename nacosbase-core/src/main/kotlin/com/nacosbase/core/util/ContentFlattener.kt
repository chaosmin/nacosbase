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

    /** Rebuild config content from an ordered list of (key, value) pairs based on [type].
     *  Keys are sorted alphabetically before assembly so the written config is always in a
     *  consistent, predictable order regardless of the order entries were added or modified. */
    fun assemble(entries: List<Pair<String, String>>, type: ConfigType): String {
        val sorted = entries.sortedBy { it.first }
        return when (type) {
            ConfigType.YAML       -> assembleYaml(sorted)
            ConfigType.PROPERTIES -> sorted.joinToString("\n") { (k, v) -> if (k.isEmpty()) v else "$k=$v" }
            ConfigType.JSON       -> assembleJson(sorted)
            ConfigType.TEXT       -> sorted.firstOrNull()?.second ?: ""
        }
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

    /**
     * Parse [value] as a YAML scalar so SnakeYAML can dump it without spurious quotes.
     * - Numbers / booleans → native type (Int, Boolean, …) → dumps unquoted
     * - Lists (starts with `- `) → native List → dumps as block sequence
     * - Plain strings → String (unchanged)
     * - Map-like strings (contain `: `) → kept as String to avoid structure corruption
     */
    private fun tryParseYamlValue(value: String): Any {
        return try {
            val parsed = Yaml().load<Any>(value)
            // Discard map results – they would corrupt the flat key-value structure
            if (parsed != null && parsed !is Map<*, *>) parsed else value
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

    // ── APPEND support ───────────────────────────────────────────────────────

    /**
     * Checks whether the value at [key] (dot-notation) in [content] can be appended to:
     * - List or scalar → OK
     * - Nested map → Failure (cannot append to an object)
     * - Key not found → Failure
     * Only YAML and JSON are supported; TEXT and PROPERTIES return Failure.
     */
    fun checkAppendable(content: String, type: ConfigType, key: String): Result<Unit> = when (type) {
        ConfigType.YAML -> {
            val root = Yaml().load<Any?>(content) ?: return Result.failure(IllegalArgumentException("Empty YAML config"))
            val value = resolveYamlPath(root, key.split("."))
                ?: return Result.failure(IllegalArgumentException("Key '$key' not found"))
            if (value is Map<*, *>)
                Result.failure(IllegalArgumentException("'$key' is a nested object; APPEND is not allowed on maps"))
            else
                Result.success(Unit)
        }
        ConfigType.JSON -> {
            val value = resolveJsonPath(Json.parseToJsonElement(content), key.split("."))
                ?: return Result.failure(IllegalArgumentException("Key '$key' not found"))
            if (value is JsonObject)
                Result.failure(IllegalArgumentException("'$key' is a nested object; APPEND is not allowed on objects"))
            else
                Result.success(Unit)
        }
        else -> Result.failure(UnsupportedOperationException("APPEND is not supported for $type"))
    }

    /**
     * Appends [newValue] to the list at [key] (dot-notation) in [content].
     * - If the current value is a list, the item is added at the end.
     * - If the current value is a scalar, it is promoted to `[oldValue, newValue]`.
     * - If the current value is a nested map, returns Failure.
     * - If the key does not exist, returns Failure.
     */
    fun appendToKey(content: String, type: ConfigType, key: String, newValue: String): Result<String> = when (type) {
        ConfigType.YAML -> appendToYamlKey(content, key, newValue)
        ConfigType.JSON -> appendToJsonKey(content, key, newValue)
        else -> Result.failure(UnsupportedOperationException("APPEND is not supported for $type"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendToYamlKey(content: String, key: String, newValue: String): Result<String> {
        val root = Yaml().load<Any?>(content) ?: return Result.failure(IllegalArgumentException("Empty YAML config"))
        if (root !is Map<*, *>) return Result.failure(IllegalArgumentException("YAML root is not a map"))
        return appendInYamlMap(root as Map<String, Any>, key.split("."), newValue)
            .map { Yaml(yamlDumperOptions()).dump(sortMapKeys(it)).trimEnd('\n') }
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendInYamlMap(
        map: Map<String, Any>,
        keys: List<String>,
        newValue: String,
    ): Result<LinkedHashMap<String, Any>> {
        val head = keys[0]
        if (!map.containsKey(head))
            return Result.failure(IllegalArgumentException("Key '${keys.joinToString(".")}' not found"))
        val updated = LinkedHashMap(map)
        if (keys.size == 1) {
            updated[head] = when (val cur = map[head]) {
                is List<*>   -> cur.toMutableList<Any?>().also { it.add(newValue) }
                is Map<*, *> -> return Result.failure(
                    IllegalArgumentException("'$head' is a nested object; APPEND is not allowed on maps"))
                null -> listOf(newValue)
                else -> listOf(cur, newValue)
            }
        } else {
            val child = map[head]
            if (child !is Map<*, *>)
                return Result.failure(IllegalArgumentException("'$head' is not a map"))
            appendInYamlMap(child as Map<String, Any>, keys.drop(1), newValue)
                .onFailure { return Result.failure(it) }
                .onSuccess  { updated[head] = it }
        }
        return Result.success(updated)
    }

    @Suppress("UNCHECKED_CAST")
    private fun sortMapKeys(map: Map<String, Any>): Map<String, Any> =
        map.entries.sortedBy { it.key }.associate { (k, v) ->
            k to if (v is Map<*, *>) sortMapKeys(v as Map<String, Any>) else v
        }

    private fun resolveYamlPath(obj: Any?, keys: List<String>): Any? {
        if (keys.isEmpty()) return obj
        if (obj !is Map<*, *>) return null
        val child = obj[keys[0]] ?: return null
        return resolveYamlPath(child, keys.drop(1))
    }

    private fun appendToJsonKey(content: String, key: String, newValue: String): Result<String> =
        runCatching {
            appendInJsonObject(Json.parseToJsonElement(content).jsonObject, key.split("."), newValue)
                .map { it.toString() }
        }.getOrElse { Result.failure(it) }

    private fun appendInJsonObject(obj: JsonObject, keys: List<String>, newValue: String): Result<JsonObject> {
        val head = keys[0]
        if (!obj.containsKey(head))
            return Result.failure(IllegalArgumentException("Key '${keys.joinToString(".")}' not found"))
        return if (keys.size == 1) {
            when (val cur = obj[head]!!) {
                is JsonArray  -> Result.success(rebuildJsonObject(obj, head,
                    JsonArray(cur + JsonPrimitive(newValue))))
                is JsonObject -> Result.failure(
                    IllegalArgumentException("'$head' is a nested object; APPEND is not allowed on objects"))
                else -> Result.success(rebuildJsonObject(obj, head,
                    JsonArray(listOf(cur, JsonPrimitive(newValue)))))
            }
        } else {
            val child = obj[head]
            if (child !is JsonObject)
                return Result.failure(IllegalArgumentException("'$head' is not an object"))
            appendInJsonObject(child, keys.drop(1), newValue).map { rebuildJsonObject(obj, head, it) }
        }
    }

    private fun rebuildJsonObject(obj: JsonObject, replaceKey: String, replaceWith: JsonElement): JsonObject =
        buildJsonObject {
            obj.entries.sortedBy { it.key }.forEach { (k, v) ->
                put(k, if (k == replaceKey) replaceWith else v)
            }
        }

    private fun resolveJsonPath(element: JsonElement, keys: List<String>): JsonElement? {
        if (keys.isEmpty()) return element
        if (element !is JsonObject) return null
        val child = element[keys[0]] ?: return null
        return resolveJsonPath(child, keys.drop(1))
    }

    // ── DELETE list-value support ─────────────────────────────────────────────

    /**
     * Checks whether [value] can be removed from the list at [key] (dot-notation) in [content]:
     * - Key exists and its value is a list that contains [value] → OK
     * - Key is not a list → Failure
     * - [value] is not present in the list → Failure
     * Only YAML and JSON are supported.
     */
    fun checkListValueRemovable(content: String, type: ConfigType, key: String, value: String): Result<Unit> =
        when (type) {
            ConfigType.YAML -> {
                val root = Yaml().load<Any?>(content)
                    ?: return Result.failure(IllegalArgumentException("Empty YAML config"))
                val cur = resolveYamlPath(root, key.split("."))
                    ?: return Result.failure(IllegalArgumentException("Key '$key' not found"))
                when {
                    cur !is List<*> ->
                        Result.failure(IllegalArgumentException("'$key' is not a list; cannot delete a specific value"))
                    cur.none { it?.toString() == value } ->
                        Result.failure(IllegalArgumentException("Value '$value' not found in list '$key'"))
                    else -> Result.success(Unit)
                }
            }
            ConfigType.JSON -> {
                val cur = resolveJsonPath(Json.parseToJsonElement(content), key.split("."))
                    ?: return Result.failure(IllegalArgumentException("Key '$key' not found"))
                when {
                    cur !is JsonArray ->
                        Result.failure(IllegalArgumentException("'$key' is not a list; cannot delete a specific value"))
                    cur.none { it is JsonPrimitive && it.content == value } ->
                        Result.failure(IllegalArgumentException("Value '$value' not found in list '$key'"))
                    else -> Result.success(Unit)
                }
            }
            else -> Result.failure(UnsupportedOperationException("List value deletion is not supported for $type"))
        }

    /**
     * Removes [value] from the list at [key] (dot-notation) in [content].
     * If the list becomes empty the key is removed entirely.
     * If the entire config becomes empty an empty string is returned (caller should delete the config).
     */
    fun removeListValue(content: String, type: ConfigType, key: String, value: String): Result<String> = when (type) {
        ConfigType.YAML -> removeYamlListValue(content, key, value)
        ConfigType.JSON -> removeJsonListValue(content, key, value)
        else -> Result.failure(UnsupportedOperationException("List value deletion is not supported for $type"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeYamlListValue(content: String, key: String, value: String): Result<String> {
        val root = Yaml().load<Any?>(content) ?: return Result.failure(IllegalArgumentException("Empty YAML config"))
        if (root !is Map<*, *>) return Result.failure(IllegalArgumentException("YAML root is not a map"))
        return removeYamlListItem(root as Map<String, Any>, key.split("."), value)
            .map { newMap ->
                if (newMap.isEmpty()) "" else Yaml(yamlDumperOptions()).dump(sortMapKeys(newMap)).trimEnd('\n')
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeYamlListItem(
        map: Map<String, Any>,
        keys: List<String>,
        value: String,
    ): Result<LinkedHashMap<String, Any>> {
        val head = keys[0]
        if (!map.containsKey(head))
            return Result.failure(IllegalArgumentException("Key '${keys.joinToString(".")}' not found"))
        val updated = LinkedHashMap(map)
        if (keys.size == 1) {
            val cur = map[head]
            if (cur !is List<*>)
                return Result.failure(IllegalArgumentException("'$head' is not a list"))
            val remaining = cur.filter { it?.toString() != value }
            if (remaining.isEmpty()) updated.remove(head) else updated[head] = remaining
        } else {
            val child = map[head]
            if (child !is Map<*, *>)
                return Result.failure(IllegalArgumentException("'$head' is not a map"))
            removeYamlListItem(child as Map<String, Any>, keys.drop(1), value)
                .onFailure { return Result.failure(it) }
                .onSuccess { childResult ->
                    // prune empty intermediate maps
                    if (childResult.isEmpty()) updated.remove(head) else updated[head] = childResult
                }
        }
        return Result.success(updated)
    }

    private fun removeJsonListValue(content: String, key: String, value: String): Result<String> =
        runCatching {
            removeJsonListItem(Json.parseToJsonElement(content).jsonObject, key.split("."), value)
                .map { it.toString() }
        }.getOrElse { Result.failure(it) }

    private fun removeJsonListItem(obj: JsonObject, keys: List<String>, value: String): Result<JsonObject> {
        val head = keys[0]
        if (!obj.containsKey(head))
            return Result.failure(IllegalArgumentException("Key '${keys.joinToString(".")}' not found"))
        return if (keys.size == 1) {
            val cur = obj[head]!!
            if (cur !is JsonArray)
                return Result.failure(IllegalArgumentException("'$head' is not a list"))
            val remaining = cur.filter { it !is JsonPrimitive || it.content != value }
            if (remaining.isEmpty()) {
                Result.success(buildJsonObject {
                    obj.entries.sortedBy { it.key }.forEach { (k, v) -> if (k != head) put(k, v) }
                })
            } else {
                Result.success(rebuildJsonObject(obj, head, JsonArray(remaining)))
            }
        } else {
            val child = obj[head]
            if (child !is JsonObject)
                return Result.failure(IllegalArgumentException("'$head' is not an object"))
            removeJsonListItem(child, keys.drop(1), value).map { childResult ->
                if (childResult.isEmpty()) {
                    buildJsonObject { obj.entries.sortedBy { it.key }.forEach { (k, v) -> if (k != head) put(k, v) } }
                } else {
                    rebuildJsonObject(obj, head, childResult)
                }
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
