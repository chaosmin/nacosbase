package com.nacosbase.core.engine

import com.nacosbase.core.model.*
import com.nacosbase.core.port.ChangeLogPort
import com.nacosbase.core.port.NacosPort
import com.nacosbase.core.port.ScriptLoaderPort
import com.nacosbase.core.util.ContentFlattener
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
                    else -> {
                        validateScript(script)
                        applyScript(script)
                    }
                }
            }
        } finally {
            changelog.releaseLock()
        }
    }

    /**
     * Validates all changesets in [script] against the current Nacos state.
     * Collects every failing condition and throws a single error listing all of them.
     * Nothing is executed if any validation error is found.
     */
    private fun validateScript(script: ChangeScript) {
        val errors = mutableListOf<String>()
        var idx = 1

        for (cs in script.changeSets) {
            val namespaceId = runCatching { nacos.resolveNamespaceId(cs.namespace) }.getOrElse { ex ->
                errors.add("[$idx] ${ex.message}")
                idx++
                null
            } ?: continue
            val existing = nacos.fetchAll(namespaceId).find { it.dataId == cs.dataId && it.group == cs.group }
            val type = cs.type ?: ConfigType.TEXT

            when (cs.action) {
                Action.ADD -> if (existing != null && cs.content != null) {
                    val existingKeys = ContentFlattener.flatten(existing.content, existing.type)
                        .map { it.first }.toSet()
                    ContentFlattener.flatten(cs.content, type)
                        .filter { (k, _) -> k.isNotEmpty() && k in existingKeys }
                        .forEach { (k, _) -> errors.add("[$idx] $k existed"); idx++ }
                }
                Action.MODIFY -> {
                    if (existing == null) {
                        errors.add("[$idx] '${cs.dataId}' not found"); idx++
                    } else if (cs.content != null) {
                        val existingKeys = ContentFlattener.flatten(existing.content, existing.type)
                            .map { it.first }.toSet()
                        ContentFlattener.flatten(cs.content, type)
                            .filter { (k, _) -> k.isNotEmpty() && k !in existingKeys }
                            .forEach { (k, _) -> errors.add("[$idx] $k not found"); idx++ }
                    }
                }
                Action.DELETE -> {
                    if (existing == null) {
                        errors.add("[$idx] '${cs.dataId}' not found"); idx++
                    } else if (cs.content != null) {
                        val existingKeys = ContentFlattener.flatten(existing.content, existing.type)
                            .map { it.first }.toSet()
                        ContentFlattener.flatten(cs.content, type)
                            .filter { (k, _) -> k.isNotEmpty() && k !in existingKeys }
                            .forEach { (k, _) -> errors.add("[$idx] $k not found"); idx++ }
                    }
                }
            }
        }

        require(errors.isEmpty()) {
            "Validation failed for '${script.scriptName}':\n${errors.joinToString("\n")}"
        }
    }

    private fun applyScript(script: ChangeScript) {
        val startMs = System.currentTimeMillis()
        val rollbackSnapshots = mutableListOf<NacosConfig>()
        runCatching {
            for (cs in script.changeSets) {
                val namespaceId = nacos.resolveNamespaceId(cs.namespace)
                cs.content?.let { ConfigValidator.validate(it, cs.type ?: ConfigType.TEXT).getOrThrow() }
                when (cs.action) {
                    Action.ADD -> {
                        val content = requireNotNull(cs.content) { "content required for ADD action on '${cs.dataId}'" }
                        val type = requireNotNull(cs.type) { "type required for ADD action on '${cs.dataId}'" }
                        val existing = nacos.fetchAll(namespaceId)
                            .find { it.dataId == cs.dataId && it.group == cs.group }
                        if (existing == null) {
                            // Config does not exist — create it
                            val added = NacosConfig(cs.dataId, cs.group, namespaceId, content, type)
                            nacos.publish(added)
                            rollbackSnapshots.add(added.copy(content = "\u0000ADD_ROLLBACK"))
                        } else {
                            // Config already exists — merge new keys in; fail on key conflicts
                            val existingKv = ContentFlattener.flatten(existing.content, existing.type)
                            val newKv = ContentFlattener.flatten(content, type)
                            val existingKeys = existingKv.map { it.first }.toSet()
                            val conflicts = newKv.filter { it.first.isNotEmpty() && it.first in existingKeys }
                            require(conflicts.isEmpty()) {
                                "Keys already exist in '${cs.dataId}': ${conflicts.map { it.first }}. Use MODIFY to update them."
                            }
                            val merged = ContentFlattener.assemble(existingKv + newKv, type)
                            rollbackSnapshots.add(existing)
                            nacos.publish(existing.copy(content = merged))
                        }
                    }
                    Action.MODIFY -> {
                        val existing = nacos.fetchAll(namespaceId)
                            .find { it.dataId == cs.dataId && it.group == cs.group }
                            ?: error("DataID '${cs.dataId}' not found in Nacos. Cannot MODIFY.")
                        rollbackSnapshots.add(existing)
                        val content = requireNotNull(cs.content) { "content required for MODIFY action on '${cs.dataId}'" }
                        val type = requireNotNull(cs.type) { "type required for MODIFY action on '${cs.dataId}'" }
                        // Merge: update only the specified keys, preserve the rest
                        val existingKv = ContentFlattener.flatten(existing.content, existing.type).toMutableList()
                        val updates = ContentFlattener.flatten(content, type).toMap()
                        val merged = existingKv
                            .map { (k, v) -> k to (updates[k] ?: v) }
                            .let { base ->
                                val newKeys = updates.keys - base.map { it.first }.toSet()
                                base + newKeys.map { k -> k to updates.getValue(k) }
                            }
                        nacos.publish(existing.copy(content = ContentFlattener.assemble(merged, type), type = type))
                    }
                    Action.DELETE -> {
                        val existing = nacos.fetchAll(namespaceId)
                            .find { it.dataId == cs.dataId && it.group == cs.group }
                            ?: error("DataID '${cs.dataId}' not found in Nacos. Cannot DELETE.")
                        rollbackSnapshots.add(existing)
                        if (cs.content != null) {
                            // Delete specific keys from the config
                            val keysToDelete = ContentFlattener.flatten(cs.content, cs.type ?: existing.type)
                                .map { it.first }.filter { it.isNotEmpty() }.toSet()
                            val remaining = ContentFlattener.flatten(existing.content, existing.type)
                                .filter { it.first !in keysToDelete }
                            if (remaining.isEmpty()) {
                                nacos.delete(cs.dataId, cs.group, namespaceId)
                            } else {
                                nacos.publish(existing.copy(content = ContentFlattener.assemble(remaining, existing.type)))
                            }
                        } else {
                            nacos.delete(cs.dataId, cs.group, namespaceId)
                        }
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
        val namespaceId = nacos.resolveNamespaceId(namespace)
        nacos.fetchAll(namespaceId).map { config ->
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
                dataId    = requireNotNull(obj["dataId"]) { "rollbackData JSON missing 'dataId'" }.jsonPrimitive.content,
                group     = requireNotNull(obj["group"]) { "rollbackData JSON missing 'group'" }.jsonPrimitive.content,
                namespace = requireNotNull(obj["namespace"]) { "rollbackData JSON missing 'namespace'" }.jsonPrimitive.content,
                content   = requireNotNull(obj["content"]) { "rollbackData JSON missing 'content'" }.jsonPrimitive.content,
                type      = ConfigType.valueOf(requireNotNull(obj["type"]) { "rollbackData JSON missing 'type'" }.jsonPrimitive.content),
            )
        }
    }
}
