package com.nacosbase.cli

import com.nacosbase.core.engine.ChangeEngine
import com.nacosbase.infra.config.ConfigLoader
import com.nacosbase.infra.csv.CsvScriptLoader
import com.nacosbase.infra.db.ChangeLogAdapterFactory
import com.nacosbase.infra.nacos.NacosClientAdapter
import java.nio.file.Path

object AppContext {
    fun buildEngine(configPath: Path = Path.of("nacosbase.yml")): ChangeEngine {
        val config = ConfigLoader.load(configPath).getOrElse { ex ->
            throw IllegalStateException("Failed to load nacosbase.yml: ${ex.message}", ex)
        }
        val nacosAdapter  = NacosClientAdapter(config.nacos)
        val changeLogPort = ChangeLogAdapterFactory.create(config.datasource)
        val scriptLoader  = CsvScriptLoader()
        return ChangeEngine(nacosAdapter, changeLogPort, scriptLoader, config.changelog.appliedBy)
    }
}
