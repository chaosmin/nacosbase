package com.nacosbase.core.port

import com.nacosbase.core.model.ChangeRecord
import java.time.Instant

interface ChangeLogPort {
    /** Returns all records sorted by id ASC (DB insertion order). */
    fun findAll(): List<ChangeRecord>

    /**
     * Persists a record for any status (SUCCESS, FAILED, ROLLED_BACK).
     * If a record for [record.scriptName] already exists, it is overwritten.
     */
    fun saveRecord(record: ChangeRecord)

    fun markRolledBack(record: ChangeRecord, rolledBackAt: Instant)

    /** Acquires a distributed lock; returns false if already locked. */
    fun acquireLock(): Boolean

    fun releaseLock()
}
