package com.nacosbase.infra.db

import com.nacosbase.core.model.Action
import com.nacosbase.core.model.ChangeRecord
import com.nacosbase.core.model.ChangeSet
import com.nacosbase.core.model.ConfigType
import com.nacosbase.core.model.ExecutionStatus
import com.nacosbase.infra.config.DatasourceConfig
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for MysqlChangeLogAdapter.
 *
 * Requires Docker. Tests are automatically disabled when Docker is unavailable.
 *
 * Container reuse: to skip the ~30s MySQL startup on repeated runs, add
 *   testcontainers.reuse.enable=true
 * to ~/.testcontainers.properties and the container will be kept alive between runs.
 */
@Testcontainers(disabledWithoutDocker = true)
class MysqlChangeLogAdapterTest {

    companion object {
        @Container
        @JvmStatic
        private val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withReuse(true)

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
        transaction(db) {
            ChangelogItemTable.deleteAll()
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

    @Test
    fun `saveRecord persists changelog items linked to the record`() {
        val items = listOf(
            ChangeSet(
                action = Action.ADD,
                dataId = "app.yml",
                group = "DEFAULT_GROUP",
                namespace = "dev",
                content = "redis:\n  host: localhost",
                type = ConfigType.YAML,
                description = "add redis config",
                operator = "Hugo",
            ),
            ChangeSet(
                action = Action.MODIFY,
                dataId = "db.yml",
                group = "DEFAULT_GROUP",
                namespace = "dev",
                content = "url: jdbc:mysql://localhost/mydb",
                type = ConfigType.YAML,
                description = "update db url",
                operator = "Hugo",
            ),
        )
        val record = buildRecord(scriptName = "010-items.csv").copy(items = items)
        adapter.saveRecord(record)

        val savedItems = transaction(db) {
            val changelogId = ChangelogTable
                .selectAll()
                .where { ChangelogTable.scriptName eq "010-items.csv" }
                .single()[ChangelogTable.id]
            ChangelogItemTable.selectAll()
                .where { ChangelogItemTable.changelogId eq changelogId }
                .toList()
        }

        assertEquals(2, savedItems.size, "Expected two items saved")
        val first = savedItems[0]
        assertEquals("ADD", first[ChangelogItemTable.action])
        assertEquals("app.yml", first[ChangelogItemTable.dataId])
        assertEquals("DEFAULT_GROUP", first[ChangelogItemTable.configGroup])
        assertEquals("dev", first[ChangelogItemTable.namespace])
        assertEquals("YAML", first[ChangelogItemTable.type])
        assertEquals("add redis config", first[ChangelogItemTable.description])
        assertEquals("Hugo", first[ChangelogItemTable.operator])
    }

    @Test
    fun `saveRecord with same scriptName replaces old items`() {
        val original = buildRecord(scriptName = "011-replace.csv").copy(
            items = listOf(
                ChangeSet(Action.ADD, "old.yml", "G", "dev", null, null, null),
            )
        )
        adapter.saveRecord(original)

        val updated = buildRecord(scriptName = "011-replace.csv", checksum = "new-cs").copy(
            items = listOf(
                ChangeSet(Action.MODIFY, "new.yml", "G", "dev", null, null, null),
                ChangeSet(Action.DELETE, "another.yml", "G", "dev", null, null, null),
            )
        )
        adapter.saveRecord(updated)

        val savedItems = transaction(db) {
            val changelogId = ChangelogTable
                .selectAll()
                .where { ChangelogTable.scriptName eq "011-replace.csv" }
                .single()[ChangelogTable.id]
            ChangelogItemTable.selectAll()
                .where { ChangelogItemTable.changelogId eq changelogId }
                .toList()
        }

        assertEquals(2, savedItems.size, "Old items should be replaced with new items")
        assertEquals("MODIFY", savedItems[0][ChangelogItemTable.action])
        assertEquals("DELETE", savedItems[1][ChangelogItemTable.action])
    }
}
