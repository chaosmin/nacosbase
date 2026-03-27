package com.nacosbase.infra.db

import com.nacosbase.core.model.ChangeRecord
import com.nacosbase.core.model.ExecutionStatus
import com.nacosbase.core.port.ChangeLogPort
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.net.InetAddress
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class MysqlChangeLogAdapter(private val db: Database) : ChangeLogPort {

    override fun findAll(): List<ChangeRecord> = transaction(db) {
        ChangelogTable.selectAll()
            .orderBy(ChangelogTable.id)
            .map { it.toRecord() }
    }

    override fun saveRecord(record: ChangeRecord) {
        transaction(db) {
            ChangelogTable.deleteWhere { scriptName eq record.scriptName }
            ChangelogTable.insert {
                it[scriptName]   = record.scriptName
                it[checksum]     = record.checksum
                it[appliedAt]    = record.appliedAt.toLocalDateTimeUtc()
                it[appliedBy]    = record.appliedBy
                it[executionMs]  = record.executionMs
                it[status]       = record.status.name
                it[rollbackData] = record.rollbackData
            }
        }
    }

    override fun markRolledBack(record: ChangeRecord, rolledBackAt: Instant) {
        transaction(db) {
            ChangelogTable.update({ ChangelogTable.scriptName eq record.scriptName }) {
                it[status]                   = ExecutionStatus.ROLLED_BACK.name
                it[ChangelogTable.rolledBackAt] = rolledBackAt.toLocalDateTimeUtc()
            }
        }
    }

    override fun acquireLock(): Boolean = transaction(db) {
        exec("SELECT id FROM nacosbase_lock WHERE id = 1 FOR UPDATE")
        val row = LockTable.selectAll().where { LockTable.id eq 1 }.single()
        if (row[LockTable.locked]) return@transaction false
        LockTable.update({ LockTable.id eq 1 }) {
            it[locked]   = true
            it[lockedBy] = InetAddress.getLocalHost().hostName
            it[lockedAt] = Instant.now().toLocalDateTimeUtc()
        }
        true
    }

    override fun releaseLock() {
        transaction(db) {
            LockTable.update({ LockTable.id eq 1 }) {
                it[locked]   = false
                it[lockedBy] = null
                it[lockedAt] = null
            }
        }
    }

    private fun ResultRow.toRecord() = ChangeRecord(
        id           = this[ChangelogTable.id],
        scriptName   = this[ChangelogTable.scriptName],
        checksum     = this[ChangelogTable.checksum],
        appliedAt    = this[ChangelogTable.appliedAt].toInstantUtc(),
        appliedBy    = this[ChangelogTable.appliedBy] ?: "",
        executionMs  = this[ChangelogTable.executionMs] ?: 0L,
        status       = ExecutionStatus.valueOf(this[ChangelogTable.status]),
        rollbackData = this[ChangelogTable.rollbackData],
    )

    private fun Instant.toLocalDateTimeUtc(): LocalDateTime =
        atZone(ZoneOffset.UTC).toLocalDateTime()

    private fun LocalDateTime.toInstantUtc(): Instant =
        toInstant(ZoneOffset.UTC)
}
