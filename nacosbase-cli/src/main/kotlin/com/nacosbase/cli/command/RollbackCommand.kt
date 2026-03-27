package com.nacosbase.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.nacosbase.cli.AppContext
import com.nacosbase.core.engine.ChangeEngine
import java.nio.file.Path

class RollbackCommand(
    internal val engineFactory: (Path) -> ChangeEngine = { AppContext.buildEngine(it) }
) : CliktCommand(name = "rollback", help = "Revert the last N successfully applied scripts") {

    private val count by option("--count", help = "Number of scripts to roll back").int().default(1)
    private val scripts by option("--scripts", help = "Scripts directory path").default("./changelogs")
    private val config by option("--config", help = "Config file path").default("nacosbase.yml")

    override fun run() {
        val engine = runCatching { engineFactory(Path.of(config)) }.getOrElse { ex ->
            echo("ERROR: ${ex.message}", err = true)
            throw ProgramResult(1)
        }
        engine.rollback(Path.of(scripts), count).fold(
            onSuccess = { echo("Rolled back $count script(s)") },
            onFailure = { ex ->
                echo("ERROR: ${ex.message}", err = true)
                throw ProgramResult(1)
            }
        )
    }
}
