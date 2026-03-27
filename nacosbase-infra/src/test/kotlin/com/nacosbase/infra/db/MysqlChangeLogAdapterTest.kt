package com.nacosbase.infra.db

import com.nacosbase.core.model.ChangeRecord
import com.nacosbase.core.model.ExecutionStatus
import com.nacosbase.infra.config.DatasourceConfig
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MySQLContainer
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MysqlChangeLogAdapterTest {

    companion object {
        private val dockerAvailable: Boolean by lazy {
            runCatching { DockerClientFactory.instance().client() }.isSuccess
        }

        private val mysql: MySQLContainer<*> by lazy {
            MySQLContainer("mysql:8.0").apply { start() }
        }

        private val db by lazy {
            DatabaseFactory.connect(
                DatasourceConfig(
                    url      = mysql.jdbcUrl,
                    username = mysql.username,
                    password = mysql.password,
                )
            )
        }

        private val adapter by lazy { MysqlChangeLogAdapter(db) }
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(dockerAvailable, "Docker not available — skipping Testcontainers integration tests")
        transaction(db) {
            ChangelogTable.deleteAll()
            LockTable.update({ LockTable.id eq 1 }) {
                it[LockTable.locked]   = false
                it[LockTable.lockedBy] = null
                it[LockTable.lockedAt] = null
            }
        }
    }

    private fun buildRecord(
        scriptName: String = "001-init.csv",
        checksum: String = "abc123",
        status: ExecutionStatus = ExecutionStatus.SUCCESS,
        rollbackData: String? = null,
        appliedBy: String = "nacosbase",
        executionMs: Long = 100L,
    ) = ChangeRecord(
        id           = 0L,
        scriptName   = scriptName,
        checksum     = checksum,
        appliedAt    = Instant.parse("2025-01-01T00:00:00Z"),
        appliedBy    = appliedBy,
        executionMs  = executionMs,
        status       = status,
        rollbackData = rollbackData,
    )

    @Test
    fun `saveRecord then findAll returns the record with matching fields`() {
        val record = buildRecord(
            scriptName   = "001-init.csv",
            checksum     = "checksum-001",
            status       = ExecutionStatus.SUCCESS,
            rollbackData = """[{"dataId":"app.yml","content":"old"}]""",
            appliedBy    = "ci-runner",
            executionMs  = 250L,
        )

        adapter.saveRecord(record)

        val all = adapter.findAll()
        assertEquals(1, all.size, "Expected exactly one record after saveRecord")
        val saved = all.first()
        assertEquals("001-init.csv", saved.scriptName)
        assertEquals("checksum-001", saved.checksum)
        assertEquals(ExecutionStatus.SUCCESS, saved.status)
        assertEquals("""[{"dataId":"app.yml","content":"old"}]""", saved.rollbackData)
        assertEquals("ci-runner", saved.appliedBy)
        assertEquals(250L, saved.executionMs)
    }

    @Test
    fun `saveRecord with same scriptName overwrites the existing record`() {
        val original = buildRecord(scriptName = "002-update.csv", checksum = "old-checksum")
        adapter.saveRecord(original)

        val updated = buildRecord(
            scriptName = "002-update.csv",
            checksum   = "new-checksum",
            status     = ExecutionStatus.FAILED,
        )
        adapter.saveRecord(updated)

        val all = adapter.findAll()
        assertEquals(1, all.size, "Expected only one record after overwrite")
        assertEquals("new-checksum", all.first().checksum)
        assertEquals(ExecutionStatus.FAILED, all.first().status)
    }

    @Test
    fun `findAll returns records sorted by id ASC`() {
        adapter.saveRecord(buildRecord(scriptName = "003-c.csv"))
        adapter.saveRecord(buildRecord(scriptName = "001-a.csv"))
        adapter.saveRecord(buildRecord(scriptName = "002-b.csv"))

        val all = adapter.findAll()
        assertEquals(3, all.size, "Expected three records")
        assertTrue(all[0].id < all[1].id, "First record id should be less than second")
        assertTrue(all[1].id < all[2].id, "Second record id should be less than third")
        assertEquals("003-c.csv", all[0].scriptName)
        assertEquals("001-a.csv", all[1].scriptName)
        assertEquals("002-b.csv", all[2].scriptName)
    }

    @Test
    fun `markRolledBack sets status to ROLLED_BACK and sets rolledBackAt`() {
        val record = buildRecord(scriptName = "004-rollback.csv", status = ExecutionStatus.SUCCESS)
        adapter.saveRecord(record)

        val saved = adapter.findAll().first()
        val rolledBackAt = Instant.parse("2025-06-01T12:00:00Z")
        adapter.markRolledBack(saved, rolledBackAt)

        val updated = adapter.findAll().first()
        assertEquals(ExecutionStatus.ROLLED_BACK, updated.status)

        val rolledBackAtDb = transaction(db) {
            ChangelogTable.selectAll()
                .where { ChangelogTable.scriptName eq "004-rollback.csv" }
                .single()[ChangelogTable.rolledBackAt]
        }
        assertNotNull(rolledBackAtDb, "rolledBackAt should be set after markRolledBack")
        assertEquals(
            rolledBackAt.epochSecond,
            rolledBackAtDb.toInstant(ZoneOffset.UTC).epochSecond,
            "rolledBackAt timestamp should match"
        )
    }

    @Test
    fun `acquireLock returns true when lock is free`() {
        val acquired = adapter.acquireLock()
        assertTrue(acquired, "acquireLock should return true when lock is free")
    }

    @Test
    fun `acquireLock returns false when lock is already held`() {
        val first = adapter.acquireLock()
        assertTrue(first, "First acquireLock should succeed")

        val second = adapter.acquireLock()
        assertFalse(second, "Second acquireLock should return false when lock is held")
    }

    @Test
    fun `releaseLock releases the lock so next acquireLock succeeds`() {
        val firstAcquire = adapter.acquireLock()
        assertTrue(firstAcquire, "First acquireLock should succeed")

        adapter.releaseLock()

        val secondAcquire = adapter.acquireLock()
        assertTrue(secondAcquire, "acquireLock should succeed after releaseLock")
    }
}
