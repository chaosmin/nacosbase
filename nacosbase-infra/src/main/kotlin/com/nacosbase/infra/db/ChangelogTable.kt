package com.nacosbase.infra.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object ChangelogTable : Table("nacosbase_changelog") {
    val id           = long("id").autoIncrement()
    val scriptName   = varchar("script_name", 255).uniqueIndex()
    val checksum     = varchar("checksum", 64)
    val appliedAt    = datetime("applied_at")
    val appliedBy    = varchar("applied_by", 100).nullable()
    val executionMs  = long("execution_ms").nullable()
    val status       = varchar("status", 20)
    val description  = varchar("description", 500).nullable()
    val rollbackData = text("rollback_data").nullable()
    val rolledBackAt = datetime("rolled_back_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
