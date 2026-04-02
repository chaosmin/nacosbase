package com.nacosbase.infra.db

import org.jetbrains.exposed.sql.Table

object ChangelogItemTable : Table("nacosbase_changelog_item") {
    val id          = long("id").autoIncrement()
    val changelogId = long("changelog_id")
    val action      = varchar("action", 20)
    val dataId      = varchar("data_id", 255)
    val configGroup = varchar("config_group", 255)
    val namespace   = varchar("namespace", 255)
    val content     = text("content").nullable()
    val type        = varchar("type", 20).nullable()
    val description = varchar("description", 500).nullable()
    val operator    = varchar("operator", 100).nullable()
    val targetKey   = varchar("target_key", 500).nullable()

    override val primaryKey = PrimaryKey(id)
}
