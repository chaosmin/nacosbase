package com.nacosbase.core.engine

import com.nacosbase.core.model.*
import com.nacosbase.core.port.ChangeLogPort
import com.nacosbase.core.port.NacosPort
import com.nacosbase.core.port.ScriptLoaderPort
import com.nacosbase.core.validation.ConfigValidator
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.time.Instant

class ChangeEngine(
    private val nacos: NacosPort,
    private val changelog: ChangeLogPort,
    private val scriptLoader: ScriptLoaderPort,
    private val appliedBy: String,
) {
    fun update(scriptsDir: Path): Result<Unit> = runCatching {
        require(changelog.acquireLock()) { "Another nacosbase process holds the lock. Aborting." }
        try {
            val scripts = scriptLoader.loadOrdered(scriptsDir)
            val recordMap = changelog.findAll().associateBy { it.scriptName }
            for (script in scripts) {
                val existing = recordMap[script.scriptName]
                when {
                    existing?.status == ExecutionStatus.SUCCESS && existing.checksum == script.checksum -> continue
                    existing?.status == ExecutionStatus.SUCCESS && existing.checksum != script.checksum ->
                        error("checksum mismatch for '${script.scriptName}'. " +
                              "Expected ${existing.checksum}, got ${script.checksum}. Script may have been tampered.")
                    else -> applyScript(script)
                }
            }
        } finally {
            changelog.releaseLock()
        }
    }

    private fun applyScript(script: ChangeScript) {
        val startMs = System.currentTimeMillis()
        val rollbackSnapshots = mutableListOf<NacosConfig>()
        runCatching {
            for (cs in script.changeSets) {
                validateNamespace(cs.namespace)
                cs.content?.let { ConfigValidator.validate(it, cs.type ?: ConfigType.TEXT).getOrThrow() }
                when (cs.action) {
                    Action.ADD -> {
                        val existing = nacos.fetchAll(cs.namespace)
                            .find { it.dataId == cs.dataId && it.group == cs.group }
                        require(existing == null) {
                            "DataID '${cs.dataId}' already exists in Nacos. Use MODIFY to update it."
                        }
                        val added = NacosConfig(cs.dataId, cs.group, cs.namespace, cs.content!!, cs.type!!)
                        nacos.publish(added)
                        // Sentinel marks this ADD for deletion on rollback
                        rollbackSnapshots.add(added.copy(content = "\u0000ADD_ROLLBACK"))
                    }
                    Action.MODIFY -> {
                        val existing = nacos.fetchAll(cs.namespace)
                            .find { it.dataId == cs.dataId && it.group == cs.group }
                            ?: error("DataID '${cs.dataId}' not found in Nacos. Cannot MODIFY.")
                        rollbackSnapshots.add(existing)
                        nacos.publish(existing.copy(content = cs.content!!, type = cs.type!!))
                    }
                    Action.DELETE -> {
                        val existing = nacos.fetchAll(cs.namespace)
                            .find { it.dataId == cs.dataId && it.group == cs.group }
                            ?: error("DataID '${cs.dataId}' not found in Nacos. Cannot DELETE.")
                        rollbackSnapshots.add(existing)
                        nacos.delete(cs.dataId, cs.group, cs.namespace)
                    }
                }
            }
            changelog.saveRecord(ChangeRecord(
                id = 0L,
                scriptName = script.scriptName,
                checksum = script.checksum,
                appliedAt = Instant.now(),
                appliedBy = appliedBy,
                executionMs = System.currentTimeMillis() - startMs,
                status = ExecutionStatus.SUCCESS,
                rollbackData = serializeSnapshots(rollbackSnapshots),
            ))
        }.onFailure { ex ->
            changelog.saveRecord(ChangeRecord(
                id = 0L,
                scriptName = script.scriptName,
                checksum = script.checksum,
                appliedAt = Instant.now(),
                appliedBy = appliedBy,
                executionMs = System.currentTimeMillis() - startMs,
                status = ExecutionStatus.FAILED,
                rollbackData = null,
            ))
            throw ex
        }
    }

    fun rollback(scriptsDir: Path, count: Int = 1): Result<Unit> = runCatching {
        require(changelog.acquireLock()) { "Another nacosbase process holds the lock. Aborting." }
        try {
            val toRevert = changelog.findAll()
                .filter { it.status == ExecutionStatus.SUCCESS }
                .sortedByDescending { it.id }
                .take(count)
            for (record in toRevert) {
                val snapshots = deserializeSnapshots(record.rollbackData ?: "[]")
                for (snapshot in snapshots.reversed()) {
                    if (snapshot.content == "\u0000ADD_ROLLBACK") {
                        nacos.delete(snapshot.dataId, snapshot.group, snapshot.namespace)
                    } else {
                        nacos.publish(snapshot)
                    }
                }
                changelog.markRolledBack(record, Instant.now())
            }
        } finally {
            changelog.releaseLock()
        }
    }

    fun diff(scriptsDir: Path): Result<List<ChangeSet>> = runCatching {
        val scripts = scriptLoader.loadOrdered(scriptsDir)
        val recordMap = changelog.findAll().associateBy { it.scriptName }
        scripts
            .filter { script ->
                val existing = recordMap[script.scriptName]
                existing == null ||
                existing.status == ExecutionStatus.ROLLED_BACK ||
                existing.status == ExecutionStatus.FAILED
            }
            .flatMap { it.changeSets }
    }

    fun status(scriptsDir: Path): Result<Map<String, ExecutionStatus?>> = runCatching {
        val scripts = scriptLoader.loadOrdered(scriptsDir)
        val recordMap = changelog.findAll().associateBy { it.scriptName }
        scripts.associate { it.scriptName to recordMap[it.scriptName]?.status }
    }

    fun baseline(namespace: String): Result<List<ChangeSet>> = runCatching {
        validateNamespace(namespace)
        nacos.fetchAll(namespace).map { config ->
            ChangeSet(
                action = Action.ADD,
                dataId = config.dataId,
                group = config.group,
                namespace = config.namespace,
                content = config.content,
                type = config.type,
                description = "baseline",
            )
        }
    }

    private fun validateNamespace(namespace: String) {
        require(nacos.namespaceExists(namespace)) {
            "namespace '$namespace' does not exist in Nacos. Please create it first."
        }
    }

    private fun serializeSnapshots(snapshots: List<NacosConfig>): String {
        if (snapshots.isEmpty()) return "[]"
        return JsonArray(snapshots.map { c ->
            JsonObject(mapOf(
                "dataId"    to JsonPrimitive(c.dataId),
                "group"     to JsonPrimitive(c.group),
                "namespace" to JsonPrimitive(c.namespace),
                "content"   to JsonPrimitive(c.content),
                "type"      to JsonPrimitive(c.type.name),
            ))
        }).toString()
    }

    private fun deserializeSnapshots(json: String): List<NacosConfig> {
        if (json == "[]") return emptyList()
        return Json.parseToJsonElement(json).jsonArray.map { el ->
            val obj = el.jsonObject
            NacosConfig(
                dataId    = obj["dataId"]!!.jsonPrimitive.content,
                group     = obj["group"]!!.jsonPrimitive.content,
                namespace = obj["namespace"]!!.jsonPrimitive.content,
                content   = obj["content"]!!.jsonPrimitive.content,
                type      = ConfigType.valueOf(obj["type"]!!.jsonPrimitive.content),
            )
        }
    }
}
