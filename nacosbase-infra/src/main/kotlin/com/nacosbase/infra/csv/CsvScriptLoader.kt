package com.nacosbase.infra.csv

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.nacosbase.core.model.Action
import com.nacosbase.core.model.ChangeScript
import com.nacosbase.core.model.ChangeSet
import com.nacosbase.core.model.ConfigType
import com.nacosbase.core.port.ScriptLoaderPort
import com.nacosbase.core.util.ContentFlattener
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

class CsvScriptLoader : ScriptLoaderPort {

    override fun loadOrdered(scriptsDir: Path): List<ChangeScript> {
        val dir = scriptsDir.toFile()
        require(dir.isDirectory) { "Scripts directory does not exist: $scriptsDir" }

        val csvFiles = dir.listFiles { f -> f.isFile && f.extension == "csv" }
            ?: emptyArray()

        val prefixRegex = Regex("""^(\d+)-.*\.csv$""")

        data class Entry(val prefix: Int, val file: File)

        val entries = csvFiles.mapNotNull { f ->
            val match = prefixRegex.matchEntire(f.name)
            if (match != null) Entry(match.groupValues[1].toInt(), f) else null
        }

        val duplicates = entries.groupBy { it.prefix }.filter { it.value.size > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate numeric prefixes in $scriptsDir: ${duplicates.keys.sorted()}"
        }

        return entries.sortedBy { it.prefix }.map { (_, file) ->
            val content = file.readText()
            val checksum = sha256(content)
            val changeSets = parseCsv(content, file.name)
            ChangeScript(file.name, checksum, changeSets)
        }
    }

    /**
     * CSV format: action,dataId,group,namespace,key,value,type,description,operator
     *
     * Multiple rows with the same (action,dataId,group,namespace) are grouped into
     * a single ChangeSet whose content is assembled from their key-value pairs.
     * Metadata (type, description, operator) is taken from the first row of each group.
     */
    private fun parseCsv(content: String, fileName: String): List<ChangeSet> {
        val rows = csvReader().readAllWithHeader(content)

        data class ConfigKey(val action: Action, val dataId: String, val group: String, val namespace: String)
        data class Meta(val type: ConfigType?, val description: String?, val operator: String?)
        data class Group(val meta: Meta, val kvEntries: MutableList<Pair<String, String>>)

        val grouped = LinkedHashMap<ConfigKey, Group>()

        rows.forEachIndexed { index, row ->
            val rowNum = index + 2
            val action = Action.valueOf(
                requireNotNull(row["action"]?.takeIf { it.isNotBlank() }) {
                    "$fileName row $rowNum: 'action' is required"
                }
            )
            val dataId = requireNotNull(row["dataId"]?.takeIf { it.isNotBlank() }) {
                "$fileName row $rowNum: 'dataId' is required"
            }
            val group = requireNotNull(row["group"]?.takeIf { it.isNotBlank() }) {
                "$fileName row $rowNum: 'group' is required"
            }
            val namespace = requireNotNull(row["namespace"]?.takeIf { it.isNotBlank() }) {
                "$fileName row $rowNum: 'namespace' is required"
            }
            val key = ConfigKey(action, dataId, group, namespace)

            val kvKey   = row["key"]   ?: ""
            val kvValue = row["value"] ?: ""

            grouped.getOrPut(key) {
                Group(
                    meta = Meta(
                        type        = row["type"]?.takeIf { it.isNotBlank() }?.let { ConfigType.valueOf(it) },
                        description = row["description"]?.takeIf { it.isNotBlank() },
                        operator    = row["operator"]?.takeIf { it.isNotBlank() },
                    ),
                    kvEntries = mutableListOf(),
                )
            }.kvEntries.add(kvKey to kvValue)
        }

        return grouped.entries.map { (key, grp) ->
            val content = grp.meta.type?.let { t ->
                when (key.action) {
                    // For DELETE, assemble only the keys (values are irrelevant) so the engine
                    // knows which keys to remove. Null content means "delete entire config".
                    Action.DELETE -> {
                        val keys = grp.kvEntries.filter { it.first.isNotEmpty() }
                        if (keys.isNotEmpty()) ContentFlattener.assemble(keys.map { it.first to "" }, t) else null
                    }
                    else -> ContentFlattener.assemble(grp.kvEntries, t)
                }
            }
            ChangeSet(
                action      = key.action,
                dataId      = key.dataId,
                group       = key.group,
                namespace   = key.namespace,
                content     = content,
                type        = grp.meta.type,
                description = grp.meta.description,
                operator    = grp.meta.operator,
            )
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
