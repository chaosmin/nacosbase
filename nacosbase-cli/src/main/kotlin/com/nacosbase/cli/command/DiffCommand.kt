package com.nacosbase.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.nacosbase.cli.AppContext
import com.nacosbase.core.engine.ChangeEngine
import java.nio.file.Path

class DiffCommand(
    internal val engineFactory: (Path) -> ChangeEngine = { AppContext.buildEngine(it) }
) : CliktCommand(name = "diff", help = "Preview pending changes (exit 1 if changes exist)") {

    private val scripts by option("--scripts", help = "Scripts directory path").default("./changelogs")
    private val config by option("--config", help = "Config file path").default("nacosbase.yml")

    override fun run() {
        val engine = runCatching { engineFactory(Path.of(config)) }.getOrElse { ex ->
            echo("ERROR: ${ex.message}", err = true)
            throw ProgramResult(1)
        }
        engine.diff(Path.of(scripts)).fold(
            onSuccess = { pending ->
                if (pending.isEmpty()) {
                    echo("No pending changes")
                    // exit 0 (default)
                } else {
                    pending.forEach { cs ->
                        echo("[${cs.action.name}]".padEnd(9) + " ${cs.dataId} @ ${cs.group}/${cs.namespace}")
                    }
                    throw ProgramResult(1)
                }
            },
            onFailure = { ex ->
                echo("ERROR: ${ex.message}", err = true)
                throw ProgramResult(1)
            }
        )
    }
}
