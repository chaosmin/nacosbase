package com.nacosbase.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.nacosbase.cli.command.BaselineCommand
import com.nacosbase.cli.command.DiffCommand
import com.nacosbase.cli.command.RollbackCommand
import com.nacosbase.cli.command.StatusCommand
import com.nacosbase.cli.command.UpdateCommand
import com.nacosbase.cli.command.ValidateCommand

class NacosbaseCommand : CliktCommand(name = "nacosbase") {
    override fun run() = Unit
}

fun main(args: Array<String>) = NacosbaseCommand()
    .subcommands(
        BaselineCommand(),
        UpdateCommand(),
        StatusCommand(),
        DiffCommand(),
        ValidateCommand(),
        RollbackCommand(),
    )
    .main(args)
