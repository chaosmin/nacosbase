package com.nacosbase.infra.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object LockTable : Table("nacosbase_lock") {
    val id       = integer("id")
    val locked   = bool("locked")
    val lockedBy = varchar("locked_by", 255).nullable()
    val lockedAt = datetime("locked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
