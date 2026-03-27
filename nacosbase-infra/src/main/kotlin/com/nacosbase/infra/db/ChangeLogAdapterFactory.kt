package com.nacosbase.infra.db

import com.nacosbase.core.port.ChangeLogPort
import com.nacosbase.infra.config.DatasourceConfig

object ChangeLogAdapterFactory {
    fun create(config: DatasourceConfig): ChangeLogPort {
        val db = DatabaseFactory.connect(config)
        return MysqlChangeLogAdapter(db)
    }
}
