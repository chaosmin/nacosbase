package com.nacosbase.core.engine

import com.nacosbase.core.fake.FakeChangeLogPort
import com.nacosbase.core.fake.FakeNacosPort
import com.nacosbase.core.fake.FakeScriptLoaderPort
import com.nacosbase.core.model.*
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class ChangeEngineTest {

    private val nacos = FakeNacosPort()
    private val changelog = FakeChangeLogPort()
    private val appliedBy = "test-user"

    private fun engine(scripts: List<ChangeScript> = emptyList()) =
        ChangeEngine(nacos, changelog, FakeScriptLoaderPort(scripts), appliedBy)

    private fun script(name: String, checksum: String = "abc", changeSets: List<ChangeSet> = emptyList()) =
        ChangeScript(name, checksum, changeSets)

    private fun addSet(dataId: String, namespace: String = "dev", content: String = "key: val") = ChangeSet(
        action = Action.ADD, dataId = dataId, group = "DEFAULT_GROUP",
        namespace = namespace, content = content, type = ConfigType.YAML, description = null
    )

    private fun modifySet(dataId: String, content: String, namespace: String = "dev") = ChangeSet(
        action = Action.MODIFY, dataId = dataId, group = "DEFAULT_GROUP",
        namespace = namespace, content = content, type = ConfigType.YAML, description = null
    )

    private fun deleteSet(dataId: String, namespace: String = "dev") = ChangeSet(
        action = Action.DELETE, dataId = dataId, group = "DEFAULT_GROUP",
        namespace = namespace, content = null, type = null, description = null
    )

    // --- update ---

    @Test fun `update applies pending script and records SUCCESS`() {
        nacos.namespaces.add("dev")
        val scripts = listOf(script("001-add.csv", changeSets = listOf(addSet("redis.yml"))))
        engine(scripts).update(Path.of(".")).getOrThrow()

        assertEquals("key: val", nacos.configs["dev/DEFAULT_GROUP/redis.yml"]?.content)
        val record = changelog.findAll().single()
        assertEquals("001-add.csv", record.scriptName)
        assertEquals(ExecutionStatus.SUCCESS, record.status)
    }

    @Test fun `update skips script already applied with matching checksum`() {
        nacos.namespaces.add("dev")
        changelog.saveRecord(ChangeRecord(1L, "001-add.csv", "abc", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, null))
        val scripts = listOf(script("001-add.csv", "abc", listOf(addSet("redis.yml"))))

        engine(scripts).update(Path.of(".")).getOrThrow()

        assertTrue(nacos.configs.isEmpty(), "should not have published anything")
    }

    @Test fun `update fails on checksum mismatch`() {
        nacos.namespaces.add("dev")
        changelog.saveRecord(ChangeRecord(1L, "001-add.csv", "different", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, null))
        val scripts = listOf(script("001-add.csv", "abc"))

        val result = engine(scripts).update(Path.of("."))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("checksum"))
    }

    @Test fun `update retries FAILED script`() {
        nacos.namespaces.add("dev")
        changelog.saveRecord(ChangeRecord(1L, "001-add.csv", "abc", Instant.now(), appliedBy, 0, ExecutionStatus.FAILED, null))
        val scripts = listOf(script("001-add.csv", "abc", listOf(addSet("redis.yml"))))

        engine(scripts).update(Path.of(".")).getOrThrow()

        assertEquals(ExecutionStatus.SUCCESS, changelog.findAll().single().status)
    }

    @Test fun `update re-applies ROLLED_BACK script`() {
        nacos.namespaces.add("dev")
        changelog.saveRecord(ChangeRecord(1L, "001-add.csv", "abc", Instant.now(), appliedBy, 0, ExecutionStatus.ROLLED_BACK, null))
        val scripts = listOf(script("001-add.csv", "abc", listOf(addSet("redis.yml"))))

        engine(scripts).update(Path.of(".")).getOrThrow()

        assertEquals(ExecutionStatus.SUCCESS, changelog.findAll().last().status)
    }

    @Test fun `update stops at first failure and records FAILED`() {
        nacos.namespaces.add("dev")
        nacos.publishError = RuntimeException("Nacos down")
        val scripts = listOf(
            script("001-fail.csv", "aaa", listOf(addSet("a.yml"))),
            script("002-skip.csv", "bbb", listOf(addSet("b.yml"))),
        )

        val result = engine(scripts).update(Path.of("."))
        assertTrue(result.isFailure)
        assertEquals(1, changelog.findAll().size)
        assertEquals(ExecutionStatus.FAILED, changelog.findAll().single().status)
        assertEquals("001-fail.csv", changelog.findAll().single().scriptName)
    }

    @Test fun `update fails when namespace does not exist`() {
        // "dev" not added to nacos.namespaces
        val scripts = listOf(script("001.csv", changeSets = listOf(addSet("redis.yml"))))
        val result = engine(scripts).update(Path.of("."))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("namespace"))
    }

    // --- rollback ---

    @Test fun `rollback reverts last SUCCESS record (MODIFY)`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/redis.yml"] =
            NacosConfig("redis.yml", "DEFAULT_GROUP", "dev", "new: value", ConfigType.YAML)
        val snapshot = """[{"dataId":"redis.yml","group":"DEFAULT_GROUP","namespace":"dev","content":"old: value","type":"YAML"}]"""
        changelog.saveRecord(ChangeRecord(1L, "001.csv", "abc", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, snapshot))

        engine().rollback(Path.of("."), count = 1).getOrThrow()

        assertEquals("old: value", nacos.configs["dev/DEFAULT_GROUP/redis.yml"]?.content)
        assertEquals(ExecutionStatus.ROLLED_BACK, changelog.findAll().single().status)
    }

    @Test fun `rollback deletes config that was added by the script`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/redis.yml"] =
            NacosConfig("redis.yml", "DEFAULT_GROUP", "dev", "key: val", ConfigType.YAML)
        // Sentinel: content="\u0000ADD_ROLLBACK" means "this was added, delete it on rollback"
        val sentinel = """[{"dataId":"redis.yml","group":"DEFAULT_GROUP","namespace":"dev","content":"\u0000ADD_ROLLBACK","type":"YAML"}]"""
        changelog.saveRecord(ChangeRecord(1L, "001-add.csv", "abc", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, sentinel))

        engine().rollback(Path.of("."), count = 1).getOrThrow()

        assertNull(nacos.configs["dev/DEFAULT_GROUP/redis.yml"], "ADD should be deleted on rollback")
        assertEquals(ExecutionStatus.ROLLED_BACK, changelog.findAll().single().status)
    }

    @Test fun `rollback skips FAILED and ROLLED_BACK records when counting N`() {
        changelog.saveRecord(ChangeRecord(1L, "001.csv", "aaa", Instant.now(), appliedBy, 0, ExecutionStatus.FAILED, null))
        changelog.saveRecord(ChangeRecord(2L, "002.csv", "bbb", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, "[]"))

        engine().rollback(Path.of("."), count = 1).getOrThrow()

        assertEquals(ExecutionStatus.ROLLED_BACK, changelog.findAll().find { it.scriptName == "002.csv" }?.status)
        assertEquals(ExecutionStatus.FAILED, changelog.findAll().find { it.scriptName == "001.csv" }?.status)
    }

    // --- diff ---

    @Test fun `diff returns pending change sets without modifying Nacos`() {
        nacos.namespaces.add("dev")
        val scripts = listOf(script("001-add.csv", changeSets = listOf(addSet("redis.yml"))))
        val pending = engine(scripts).diff(Path.of(".")).getOrThrow()

        assertEquals(1, pending.size)
        assertEquals("redis.yml", pending.first().dataId)
        assertTrue(nacos.configs.isEmpty(), "diff must not write to Nacos")
    }

    // --- status ---

    @Test fun `status returns applied and pending scripts`() {
        changelog.saveRecord(ChangeRecord(1L, "001.csv", "abc", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, null))
        val scripts = listOf(script("001.csv", "abc"), script("002.csv", "def"))

        val statuses = engine(scripts).status(Path.of(".")).getOrThrow()

        assertEquals(2, statuses.size)
        assertEquals(ExecutionStatus.SUCCESS, statuses["001.csv"])
        assertNull(statuses["002.csv"])
    }
}
