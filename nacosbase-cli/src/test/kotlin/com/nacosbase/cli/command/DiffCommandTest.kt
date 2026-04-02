package com.nacosbase.cli.command

import com.github.ajalt.clikt.testing.test
import com.nacosbase.core.engine.ChangeEngine
import com.nacosbase.core.model.*
import com.nacosbase.core.port.ChangeLogPort
import com.nacosbase.core.port.NacosPort
import com.nacosbase.core.port.ScriptLoaderPort
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffCommandTest {

    @Test
    fun `diff exits 1 when config file is missing`() {
        val result = DiffCommand().test(listOf("--config", "/nonexistent/nacosbase.yml", "--scripts", "./changelogs"))
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("ERROR"))
    }

    @Test
    fun `diff exits 0 when no pending changes`() {
        val result = DiffCommand(engineFactory = { _ -> makeEngine() })
            .test(listOf("--scripts", ".", "--config", "nacosbase.yml"))
        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("No pending changes"))
    }

    @Test
    fun `diff exits 1 and prints pending changes`() {
        val addSet = ChangeSet(Action.ADD, "redis.yml", "DEFAULT_GROUP", "dev", "key: val", ConfigType.YAML, null)
        val scripts = listOf(ChangeScript("001-add.csv", "abc", listOf(addSet)))
        val result = DiffCommand(engineFactory = { _ -> makeEngine(scripts) })
            .test(listOf("--scripts", ".", "--config", "nacosbase.yml"))
        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("redis.yml"))
        assertTrue(result.output.contains("ADD"))
    }
}

private fun makeEngine(changeSets: List<ChangeScript> = emptyList()): ChangeEngine {
    val nacos = object : NacosPort {
        override fun fetchAll(namespace: String) = emptyList<NacosConfig>()
        override fun publish(config: NacosConfig) {}
        override fun delete(dataId: String, group: String, namespace: String) {}
        override fun namespaceExists(namespace: String) = true
        override fun resolveNamespaceId(nameOrId: String) = nameOrId
    }
    val changelog = object : ChangeLogPort {
        override fun findAll() = emptyList<ChangeRecord>()
        override fun saveRecord(record: ChangeRecord) {}
        override fun markRolledBack(record: ChangeRecord, rolledBackAt: Instant) {}
        override fun acquireLock() = true
        override fun releaseLock() {}
    }
    val scriptLoader = object : ScriptLoaderPort {
        override fun loadOrdered(scriptsDir: Path) = changeSets
    }
    return ChangeEngine(nacos, changelog, scriptLoader, "test")
}
