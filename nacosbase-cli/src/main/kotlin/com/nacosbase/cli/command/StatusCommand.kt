package com.nacosbase.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.nacosbase.cli.AppContext
import com.nacosbase.core.engine.ChangeEngine
import java.nio.file.Path

class StatusCommand(
    internal val engineFactory: (Path) -> ChangeEngine = { AppContext.buildEngine(it) }
) : CliktCommand(name = "status", help = "List all scripts with their status") {

    private val scripts by option("--scripts", help = "Scripts directory path").default("./changelogs")
    private val config by option("--config", help = "Config file path").default("nacosbase.yml")

    override fun run() {
        val engine = runCatching { engineFactory(Path.of(config)) }.getOrElse { ex ->
            echo("ERROR: ${ex.message}", err = true)
            throw ProgramResult(1)
        }
        engine.status(Path.of(scripts)).fold(
            onSuccess = { statuses ->
                statuses.forEach { (name, status) ->
                    echo("%-40s %s".format(name, status?.name ?: "PENDING"))
                }
            },
            onFailure = { ex ->
                echo("ERROR: ${ex.message}", err = true)
                throw ProgramResult(1)
            }
        )
    }
}
