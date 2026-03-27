package com.nacosbase.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class NacosbaseCommand : CliktCommand(name = "nacosbase") {
    override fun run() = Unit
}

fun main(args: Array<String>) = NacosbaseCommand()
    .subcommands(/* commands will be added in Task 11 */)
    .main(args)
