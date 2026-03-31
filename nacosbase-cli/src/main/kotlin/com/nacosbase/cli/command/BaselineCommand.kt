package com.nacosbase.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.nacosbase.cli.AppContext
import com.nacosbase.core.engine.ChangeEngine
import com.nacosbase.core.model.ChangeSet
import com.nacosbase.core.model.ConfigType
import com.nacosbase.core.util.ContentFlattener
import java.nio.file.Path

class BaselineCommand(
    internal val engineFactory: (Path) -> ChangeEngine = { AppContext.buildEngine(it) }
) : CliktCommand(name = "baseline", help = "Export current Nacos configs as baseline CSV") {

    private val namespace by option("--namespace", help = "Nacos namespace name or ID").required()
    private val output by option("--output", help = "Output CSV file path").required()
    private val config by option("--config", help = "Config file path").default("nacosbase.yml")

    override fun run() {
        val engine = runCatching { engineFactory(Path.of(config)) }.getOrElse { ex ->
            echo("ERROR: ${ex.message}", err = true)
            throw ProgramResult(1)
        }
        engine.baseline(namespace).fold(
            onSuccess = { changeSets ->
                val file = java.io.File(output)
                file.parentFile?.mkdirs()
                file.writeText(buildCsv(changeSets))
                echo("Baseline written to $output (${changeSets.size} configs)")
            },
            onFailure = { ex ->
                echo("ERROR: ${ex.message}", err = true)
                throw ProgramResult(1)
            }
        )
    }

    private fun csvQuote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun buildCsv(changeSets: List<ChangeSet>): String {
        val sb = StringBuilder("action,dataId,group,namespace,key,value,type,description,operator\n")
        for (cs in changeSets) {
            val type = cs.type ?: ConfigType.TEXT
            val kvEntries = cs.content
                ?.let { ContentFlattener.flatten(it, type) }
                ?: listOf("" to "")
            for ((key, value) in kvEntries) {
                sb.append(
                    listOf(
                        "ADD",
                        csvQuote(cs.dataId),
                        csvQuote(cs.group),
                        csvQuote(cs.namespace),
                        csvQuote(key),
                        csvQuote(value),
                        type.name,
                        csvQuote(cs.description ?: "baseline"),
                        "system",
                    ).joinToString(",") + "\n"
                )
            }
        }
        return sb.toString()
    }
}
