package com.nacosbase.infra.csv

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.nacosbase.core.model.Action
import com.nacosbase.core.model.ChangeScript
import com.nacosbase.core.model.ChangeSet
import com.nacosbase.core.model.ConfigType
import com.nacosbase.core.port.ScriptLoaderPort
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

    private fun parseCsv(content: String, fileName: String): List<ChangeSet> {
        val rows = csvReader().readAllWithHeader(content)
        return rows.mapIndexed { index, row ->
            val rowNum = index + 2  // 1-based; header is row 1
            ChangeSet(
                action = Action.valueOf(
                    requireNotNull(row["action"]?.takeIf { it.isNotBlank() }) {
                        "$fileName row $rowNum: 'action' is required"
                    },
                ),
                dataId = requireNotNull(row["dataId"]?.takeIf { it.isNotBlank() }) {
                    "$fileName row $rowNum: 'dataId' is required"
                },
                group = requireNotNull(row["group"]?.takeIf { it.isNotBlank() }) {
                    "$fileName row $rowNum: 'group' is required"
                },
                namespace = requireNotNull(row["namespace"]?.takeIf { it.isNotBlank() }) {
                    "$fileName row $rowNum: 'namespace' is required"
                },
                content = row["content"]?.takeIf { it.isNotBlank() },
                type = row["type"]?.takeIf { it.isNotBlank() }?.let { ConfigType.valueOf(it) },
                description = row["description"]?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
