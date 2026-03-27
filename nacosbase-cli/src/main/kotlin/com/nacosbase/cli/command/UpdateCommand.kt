package com.nacosbase.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.nacosbase.cli.AppContext
import com.nacosbase.core.engine.ChangeEngine
import java.nio.file.Path

class UpdateCommand(
    internal val engineFactory: (Path) -> ChangeEngine = { AppContext.buildEngine(it) }
) : CliktCommand(name = "update", help = "Apply all pending change scripts") {

    private val scripts by option("--scripts", help = "Scripts directory path").default("./changelogs")
    private val config by option("--config", help = "Config file path").default("nacosbase.yml")

    override fun run() {
        val engine = runCatching { engineFactory(Path.of(config)) }.getOrElse { ex ->
            echo("ERROR: ${ex.message}", err = true)
            throw ProgramResult(1)
        }
        engine.update(Path.of(scripts)).fold(
            onSuccess = { echo("Update complete") },
            onFailure = { ex ->
                echo("ERROR: ${ex.message}", err = true)
                throw ProgramResult(1)
            }
        )
    }
}
