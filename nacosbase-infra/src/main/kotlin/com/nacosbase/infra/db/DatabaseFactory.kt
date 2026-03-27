package com.nacosbase.infra.db

import com.nacosbase.infra.config.DatasourceConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun connect(config: DatasourceConfig): Database {
        val db = Database.connect(
            url      = config.url,
            driver   = "com.mysql.cj.jdbc.Driver",
            user     = config.username,
            password = config.password,
        )
        transaction(db) {
            SchemaUtils.create(ChangelogTable, LockTable)
            val exists = LockTable.selectAll().where { LockTable.id eq 1 }.count() > 0
            if (!exists) {
                LockTable.insert {
                    it[id]     = 1
                    it[locked] = false
                }
            }
        }
        return db
    }
}
