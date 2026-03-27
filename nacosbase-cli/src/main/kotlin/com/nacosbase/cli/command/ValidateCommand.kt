package com.nacosbase.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.nacosbase.infra.csv.CsvScriptLoader
import java.nio.file.Path

class ValidateCommand : CliktCommand(name = "validate", help = "Validate CSV format and file naming rules") {

    private val scripts by option("--scripts", help = "Scripts directory path").required()

    override fun run() {
        val loader = CsvScriptLoader()
        runCatching { loader.loadOrdered(Path.of(scripts)) }.fold(
            onSuccess = { loadedScripts ->
                echo("Validation passed: ${loadedScripts.size} script(s) are valid")
            },
            onFailure = { ex ->
                echo("Validation failed: ${ex.message}", err = true)
                throw ProgramResult(1)
            }
        )
    }
}
