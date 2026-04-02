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

    // --- baseline ---

    @Test fun `baseline returns ADD change sets for all configs in namespace`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/redis.yml"] =
            NacosConfig("redis.yml", "DEFAULT_GROUP", "dev", "port: 6379", ConfigType.YAML)
        nacos.configs["dev/DEFAULT_GROUP/app.properties"] =
            NacosConfig("app.properties", "DEFAULT_GROUP", "dev", "timeout=3000", ConfigType.PROPERTIES)

        val result = engine().baseline("dev").getOrThrow()

        assertEquals(2, result.size)
        assertTrue(result.all { it.action == Action.ADD })
        assertTrue(result.any { it.dataId == "redis.yml" && it.content == "port: 6379" })
        assertTrue(result.any { it.dataId == "app.properties" && it.content == "timeout=3000" })
    }

    @Test fun `baseline fails when namespace does not exist`() {
        val result = engine().baseline("nonexistent")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("namespace"))
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

    // --- update: lock ---

    @Test fun `update fails immediately when lock cannot be acquired`() {
        changelog.acquireLock() // pre-lock
        nacos.namespaces.add("dev")
        val scripts = listOf(script("001.csv", changeSets = listOf(addSet("redis.yml"))))

        val result = engine(scripts).update(Path.of("."))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("lock"))
        assertTrue(nacos.configs.isEmpty())
    }

    // --- applyScript: ADD merge into existing ---

    @Test fun `update ADD merges new keys into existing config`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost", ConfigType.YAML)

        val addSet = ChangeSet(
            action = Action.ADD, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "port: 8080", type = ConfigType.YAML, description = null
        )
        engine(listOf(script("001.csv", changeSets = listOf(addSet)))).update(Path.of(".")).getOrThrow()

        val content = nacos.configs["dev/DEFAULT_GROUP/app.yml"]!!.content
        assertTrue(content.contains("host"))
        assertTrue(content.contains("port"))
    }

    @Test fun `update ADD fails on key conflict with existing config`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost", ConfigType.YAML)

        val addSet = ChangeSet(
            action = Action.ADD, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "host: conflict", type = ConfigType.YAML, description = null
        )
        val result = engine(listOf(script("001.csv", changeSets = listOf(addSet)))).update(Path.of("."))

        assertTrue(result.isFailure)
        // validation error — no record saved, original config untouched
        assertTrue(changelog.findAll().isEmpty())
        assertEquals("host: localhost", nacos.configs["dev/DEFAULT_GROUP/app.yml"]!!.content)
    }

    // --- applyScript: MODIFY ---

    @Test fun `update MODIFY updates specified key preserving others`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost\nport: 8080", ConfigType.YAML)

        engine(listOf(script("001.csv", changeSets = listOf(modifySet("app.yml", "host: redis"))))).update(Path.of(".")).getOrThrow()

        val content = nacos.configs["dev/DEFAULT_GROUP/app.yml"]!!.content
        assertTrue(content.contains("redis"))
        assertTrue(content.contains("port"))
    }

    // --- applyScript: APPEND ---

    @Test fun `update APPEND promotes scalar to list`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "server: localhost", ConfigType.YAML)

        val appendSet = ChangeSet(
            action = Action.APPEND, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "redis", type = ConfigType.YAML,
            description = null, targetKey = "server"
        )
        engine(listOf(script("001.csv", changeSets = listOf(appendSet)))).update(Path.of(".")).getOrThrow()

        val content = nacos.configs["dev/DEFAULT_GROUP/app.yml"]!!.content
        assertTrue(content.contains("localhost"))
        assertTrue(content.contains("redis"))
    }

    @Test fun `update APPEND adds item to existing list`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "servers:\n  - 192.168.1.1", ConfigType.YAML)

        val appendSet = ChangeSet(
            action = Action.APPEND, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "192.168.1.2", type = ConfigType.YAML,
            description = null, targetKey = "servers"
        )
        engine(listOf(script("001.csv", changeSets = listOf(appendSet)))).update(Path.of(".")).getOrThrow()

        val content = nacos.configs["dev/DEFAULT_GROUP/app.yml"]!!.content
        assertTrue(content.contains("192.168.1.1"))
        assertTrue(content.contains("192.168.1.2"))
    }

    @Test fun `update APPEND fails when config not found`() {
        nacos.namespaces.add("dev")
        val appendSet = ChangeSet(
            action = Action.APPEND, dataId = "missing.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "value", type = ConfigType.YAML,
            description = null, targetKey = "key"
        )
        val result = engine(listOf(script("001.csv", changeSets = listOf(appendSet)))).update(Path.of("."))
        assertTrue(result.isFailure)
        assertTrue(changelog.findAll().isEmpty(), "validation error — no record saved")
    }

    // --- applyScript: DELETE with content ---

    @Test fun `update DELETE removes specific key from config`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost\nport: 8080", ConfigType.YAML)

        val deleteSet = ChangeSet(
            action = Action.DELETE, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "port:", type = ConfigType.YAML, description = null
        )
        engine(listOf(script("001.csv", changeSets = listOf(deleteSet)))).update(Path.of(".")).getOrThrow()

        val content = nacos.configs["dev/DEFAULT_GROUP/app.yml"]!!.content
        assertTrue(!content.contains("port"))
        assertTrue(content.contains("host"))
    }

    @Test fun `update DELETE removes specific list item`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "servers:\n  - 192.168.1.1\n  - 192.168.1.2", ConfigType.YAML)

        val deleteSet = ChangeSet(
            action = Action.DELETE, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "servers: 192.168.1.1", type = ConfigType.YAML, description = null
        )
        engine(listOf(script("001.csv", changeSets = listOf(deleteSet)))).update(Path.of(".")).getOrThrow()

        val content = nacos.configs["dev/DEFAULT_GROUP/app.yml"]!!.content
        assertTrue(!content.contains("192.168.1.1"))
        assertTrue(content.contains("192.168.1.2"))
    }

    @Test fun `update DELETE deletes entire config when no content`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost", ConfigType.YAML)

        engine(listOf(script("001.csv", changeSets = listOf(deleteSet("app.yml"))))).update(Path.of(".")).getOrThrow()

        assertNull(nacos.configs["dev/DEFAULT_GROUP/app.yml"])
    }

    @Test fun `update DELETE deletes config when last key is removed`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "port: 8080", ConfigType.YAML)

        val deleteSet = ChangeSet(
            action = Action.DELETE, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "port:", type = ConfigType.YAML, description = null
        )
        engine(listOf(script("001.csv", changeSets = listOf(deleteSet)))).update(Path.of(".")).getOrThrow()

        assertNull(nacos.configs["dev/DEFAULT_GROUP/app.yml"], "config should be deleted when last key is removed")
    }

    // --- validateScript: error accumulation ---

    @Test fun `update validation fails with all errors reported at once`() {
        nacos.namespaces.add("dev")
        // MODIFY a config that doesn't exist + APPEND a config that doesn't exist
        val scripts = listOf(script("001.csv", changeSets = listOf(
            modifySet("missing1.yml", "key: val"),
            modifySet("missing2.yml", "key: val"),
        )))

        val result = engine(scripts).update(Path.of("."))

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("missing1.yml"))
        assertTrue(msg.contains("missing2.yml"))
        assertTrue(changelog.findAll().isEmpty(), "no record saved when validation fails before apply")
    }

    @Test fun `update validation fails for MODIFY when key not found in config`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost", ConfigType.YAML)

        val result = engine(listOf(script("001.csv", changeSets = listOf(
            modifySet("app.yml", "nonexistent: value")
        )))).update(Path.of("."))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("nonexistent"))
    }

    @Test fun `update validation fails for DELETE when config not found`() {
        nacos.namespaces.add("dev")

        val result = engine(listOf(script("001.csv", changeSets = listOf(
            deleteSet("nonexistent.yml")
        )))).update(Path.of("."))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("nonexistent.yml"))
    }

    @Test fun `update validation fails for APPEND when config not found`() {
        nacos.namespaces.add("dev")
        val appendSet = ChangeSet(
            action = Action.APPEND, dataId = "missing.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "val", type = ConfigType.YAML,
            description = null, targetKey = "key"
        )

        val result = engine(listOf(script("001.csv", changeSets = listOf(appendSet)))).update(Path.of("."))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("missing.yml"))
    }

    @Test fun `update validation fails for APPEND when key is missing`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost", ConfigType.YAML)

        val appendSet = ChangeSet(
            action = Action.APPEND, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "val", type = ConfigType.YAML,
            description = null, targetKey = null
        )

        val result = engine(listOf(script("001.csv", changeSets = listOf(appendSet)))).update(Path.of("."))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("key"))
    }

    @Test fun `update validation fails for ADD when key conflicts with existing config`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/app.yml"] =
            NacosConfig("app.yml", "DEFAULT_GROUP", "dev", "host: localhost", ConfigType.YAML)

        val addSet = ChangeSet(
            action = Action.ADD, dataId = "app.yml", group = "DEFAULT_GROUP",
            namespace = "dev", content = "host: conflict", type = ConfigType.YAML, description = null
        )

        val result = engine(listOf(script("001.csv", changeSets = listOf(addSet)))).update(Path.of("."))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("host"))
    }

    // --- rollback: multiple scripts ---

    @Test fun `rollback reverts N last SUCCESS records in reverse order`() {
        nacos.namespaces.add("dev")
        nacos.configs["dev/DEFAULT_GROUP/a.yml"] = NacosConfig("a.yml", "DEFAULT_GROUP", "dev", "new: a", ConfigType.YAML)
        nacos.configs["dev/DEFAULT_GROUP/b.yml"] = NacosConfig("b.yml", "DEFAULT_GROUP", "dev", "new: b", ConfigType.YAML)

        val snapA = """[{"dataId":"a.yml","group":"DEFAULT_GROUP","namespace":"dev","content":"old: a","type":"YAML"}]"""
        val snapB = """[{"dataId":"b.yml","group":"DEFAULT_GROUP","namespace":"dev","content":"old: b","type":"YAML"}]"""
        changelog.saveRecord(ChangeRecord(1L, "001.csv", "aaa", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, snapA))
        changelog.saveRecord(ChangeRecord(2L, "002.csv", "bbb", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, snapB))

        engine().rollback(Path.of("."), count = 2).getOrThrow()

        assertEquals("old: a", nacos.configs["dev/DEFAULT_GROUP/a.yml"]!!.content)
        assertEquals("old: b", nacos.configs["dev/DEFAULT_GROUP/b.yml"]!!.content)
        assertTrue(changelog.findAll().all { it.status == ExecutionStatus.ROLLED_BACK })
    }

    // --- diff ---

    @Test fun `diff includes FAILED and ROLLED_BACK scripts`() {
        nacos.namespaces.add("dev")
        changelog.saveRecord(ChangeRecord(1L, "001.csv", "aaa", Instant.now(), appliedBy, 0, ExecutionStatus.FAILED, null))
        changelog.saveRecord(ChangeRecord(2L, "002.csv", "bbb", Instant.now(), appliedBy, 0, ExecutionStatus.ROLLED_BACK, null))
        changelog.saveRecord(ChangeRecord(3L, "003.csv", "ccc", Instant.now(), appliedBy, 0, ExecutionStatus.SUCCESS, null))

        val scripts = listOf(
            script("001.csv", "aaa", listOf(addSet("a.yml"))),
            script("002.csv", "bbb", listOf(addSet("b.yml"))),
            script("003.csv", "ccc", listOf(addSet("c.yml"))),
        )
        val pending = engine(scripts).diff(Path.of(".")).getOrThrow()

        assertEquals(2, pending.size)
        assertTrue(pending.any { it.dataId == "a.yml" })
        assertTrue(pending.any { it.dataId == "b.yml" })
        assertTrue(pending.none { it.dataId == "c.yml" })
    }
}
