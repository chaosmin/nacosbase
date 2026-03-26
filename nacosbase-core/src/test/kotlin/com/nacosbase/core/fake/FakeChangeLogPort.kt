package com.nacosbase.core.fake

import com.nacosbase.core.model.ChangeRecord
import com.nacosbase.core.model.ExecutionStatus
import com.nacosbase.core.port.ChangeLogPort
import java.time.Instant

class FakeChangeLogPort : ChangeLogPort {
    private val records = mutableListOf<ChangeRecord>()
    private var locked = false
    private var nextId = 1L

    override fun findAll(): List<ChangeRecord> = records.sortedBy { it.id }

    override fun saveRecord(record: ChangeRecord) {
        val withId = if (record.id == 0L) record.copy(id = nextId++) else record
        records.removeIf { it.scriptName == withId.scriptName }
        records.add(withId)
    }

    override fun markRolledBack(record: ChangeRecord, rolledBackAt: Instant) {
        val idx = records.indexOfFirst { it.scriptName == record.scriptName }
        if (idx >= 0) records[idx] = records[idx].copy(status = ExecutionStatus.ROLLED_BACK)
    }

    override fun acquireLock(): Boolean {
        if (locked) return false
        locked = true
        return true
    }

    override fun releaseLock() { locked = false }
}
