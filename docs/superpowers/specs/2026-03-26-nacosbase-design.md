# nacosbase Design Spec

**Date:** 2026-03-26
**Status:** Approved

---

## Overview

nacosbase is a Nacos configuration version management tool inspired by [Liquibase](https://www.liquibase.org/). It brings database-migration-style discipline to Nacos config management: baseline generation, CSV-based change scripts, execution tracking with idempotency, format validation, multi-environment promotion, and rollback.

**Delivery:** Both a CLI tool (fat-jar) and an embeddable library.

---

## Module Structure

Three Gradle modules with a strict one-way dependency chain:

```
nacosbase-cli → nacosbase-infra → nacosbase-core
```

All three modules must be declared in `settings.gradle.kts`:

```kotlin
include("nacosbase-core", "nacosbase-infra", "nacosbase-cli")
```

### `nacosbase-core` — Domain Layer

Pure Kotlin, zero IO dependencies. Contains all business rules, domain models, and port interfaces.

```
com.nacosbase.core
├── model/        # NacosConfig, ChangeScript, ChangeSet, ChangeRecord, enums
├── engine/       # ChangeEngine — orchestrates the main workflow
├── port/         # NacosPort, ChangeLogPort, ScriptLoaderPort (interfaces)
└── validation/   # Format validation and canonical sort rules
```

### `nacosbase-infra` — Infrastructure Layer

Implements the port interfaces from `nacosbase-core`. Contains all IO concerns.

```
com.nacosbase.infra
├── nacos/        # NacosClientAdapter (Nacos Java SDK 2.x)
├── db/           # MysqlChangeLogAdapter (Exposed ORM + mysql-connector-j)
├── csv/          # CsvScriptLoader (kotlin-csv)
└── config/       # NacosbaseConfig (kaml + env var override)
```

### `nacosbase-cli` — CLI Entry Point

Assembles all layers and packages as an executable fat-jar.

```
com.nacosbase.cli
├── Main.kt
└── command/      # baseline, update, status, diff, rollback, validate
```

Library users depend on `nacosbase-core` + `nacosbase-infra` only.

---

## Domain Model

### Core Models (`nacosbase-core`)

```kotlin
enum class ConfigType { YAML, PROPERTIES, JSON, TEXT }  // TEXT bypasses format validation
enum class Action { ADD, MODIFY, DELETE }
enum class ExecutionStatus { SUCCESS, FAILED, ROLLED_BACK }

data class NacosConfig(
    val dataId: String,
    val group: String,
    val namespace: String,
    val content: String,
    val type: ConfigType,
)

data class ChangeSet(
    val action: Action,
    val dataId: String,
    val group: String,
    val namespace: String,
    val content: String?,     // null for DELETE
    val type: ConfigType?,
    val description: String?, // per-row description for audit trail
)

data class ChangeScript(
    val scriptName: String,   // e.g. "001-init-redis.csv"
    val checksum: String,     // SHA-256 of file content
    val changeSets: List<ChangeSet>,
)

data class ChangeRecord(
    val id: Long,             // DB auto-increment; used for ordering in rollback
    val scriptName: String,
    val checksum: String,
    val appliedAt: Instant,
    val appliedBy: String,
    val executionMs: Long,
    val status: ExecutionStatus,
    val rollbackData: String?,  // JSON array of pre-change NacosConfig snapshots
)
```

### Port Interfaces (`nacosbase-core`)

```kotlin
interface NacosPort {
    fun fetchAll(namespace: String): List<NacosConfig>
    fun publish(config: NacosConfig)
    fun delete(dataId: String, group: String, namespace: String)
    fun namespaceExists(namespace: String): Boolean
}

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

interface ScriptLoaderPort {
    /**
     * Loads and returns all .csv files in [scriptsDir] sorted by numeric prefix ASC.
     * Rules:
     * - Files must be named {N}-{description}.csv where N is a non-negative integer (zero-padded or not).
     * - Duplicate numeric prefixes are an error.
     * - Non-.csv files and subdirectories are silently ignored.
     * - Numeric prefixes need not be contiguous (gaps allowed).
     */
    fun loadOrdered(scriptsDir: Path): List<ChangeScript>
}
```

---

## CSV Change Script Format

**File naming:** `{N}-{描述}.csv` — sorted and executed by numeric prefix ascending.

```csv
action,dataId,group,namespace,content,type,description
ADD,redis.yml,DEFAULT_GROUP,dev,"server: redis\nport: 6379",YAML,初始化Redis配置
MODIFY,app.properties,DEFAULT_GROUP,dev,"timeout=3000",PROPERTIES,调整超时
DELETE,old-config.yml,DEFAULT_GROUP,dev,,YAML,清理废弃配置
```

**Action semantics:**
- `ADD` — create new DataID; error if already exists in Nacos
- `MODIFY` — overwrite existing DataID content; error if not found in Nacos
- `DELETE` — remove DataID; error if not found in Nacos; `content` column must be empty
- `namespace` must reference an existing Nacos namespace; missing namespace is a precondition error with a clear message (nacosbase does not create namespaces)

**Rollback:** the infra layer captures a JSON snapshot of the pre-change `NacosConfig` before each `MODIFY` or `DELETE` and stores it in `rollback_data`. Rollback replays these snapshots in reverse order.

**`baseline` output:** every row uses `action=ADD` and `namespace` is the Nacos namespace ID (UUID string as returned by the Nacos API). The output file is a valid change script that can be re-applied to recreate the baseline state.

---

## Execution Log (MySQL)

```sql
CREATE TABLE nacosbase_changelog (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_name     VARCHAR(255) NOT NULL UNIQUE,
    checksum        VARCHAR(64)  NOT NULL,
    applied_at      DATETIME     NOT NULL,
    applied_by      VARCHAR(100),
    execution_ms    BIGINT,
    status          VARCHAR(20)  NOT NULL,   -- SUCCESS | FAILED | ROLLED_BACK
    description     VARCHAR(500),            -- aggregated from all ChangeSet.description in the script
    rollback_data   JSON,                    -- pre-change snapshot array for rollback
    rolled_back_at  DATETIME                 -- set when status = ROLLED_BACK
);

-- Distributed lock table (one row, always present)
CREATE TABLE nacosbase_lock (
    id          INT  NOT NULL DEFAULT 1 PRIMARY KEY,
    locked      BOOL NOT NULL DEFAULT FALSE,
    locked_by   VARCHAR(255),
    locked_at   DATETIME
);
INSERT INTO nacosbase_lock (id) VALUES (1);
```

**Idempotency rules (evaluated per script in `id ASC` order):**
- No record found, or record has `status=ROLLED_BACK` → apply and save record
- Record has `status=SUCCESS` with matching checksum → skip silently
- Record has `status=SUCCESS` with mismatched checksum → halt with error (tamper detected)
- Record has `status=FAILED` → retry: apply again and overwrite the record via `saveRecord()`

**`rollback` semantics:** selects the last N records where `status=SUCCESS`, sorted by `id DESC`. Records with `status=FAILED` or `status=ROLLED_BACK` are skipped when counting N.

**Concurrent execution:** `update` and `rollback` acquire a row-level lock on `nacosbase_lock` (MySQL `SELECT ... FOR UPDATE`) before reading the changelog. If the lock is held, the command fails immediately with an error message. The lock is released after the operation completes or on error.

**Multi-script failure policy:** `update` stops at the first failed script. Scripts already applied in the same run are left in place (not rolled back). The failed script is persisted via `saveRecord()` with `status=FAILED`. Subsequent runs will retry the failed script per the idempotency rules above.

---

## CLI Commands

Built with [Clikt](https://ajalt.github.io/clikt/).

| Command | Description |
|---------|-------------|
| `baseline` | Export current Nacos configs as a baseline CSV file |
| `update` | Apply all pending (or previously FAILED) change scripts in order |
| `status` | List all scripts: applied / pending / failed / checksum mismatch |
| `diff` | Preview what `update` would do; exits 0 if no changes, 1 if changes exist |
| `validate` | Validate CSV format and file naming rules; no Nacos or DB connection |
| `rollback` | Revert the last N successfully applied scripts (default: 1) |

```bash
nacosbase baseline --output ./changelogs/000-baseline.csv
nacosbase diff     --scripts ./changelogs/           # exits 1 if pending changes exist
nacosbase update   --scripts ./changelogs/
nacosbase status
nacosbase rollback --count 2
nacosbase validate --scripts ./changelogs/           # also checks for duplicate prefixes
```

**`diff` output format:** plain text to stdout, one line per pending change set:
```
[ADD]    redis.yml @ DEFAULT_GROUP/dev
[MODIFY] app.properties @ DEFAULT_GROUP/dev
[DELETE] old-config.yml @ DEFAULT_GROUP/dev
```

---

## Configuration

**`nacosbase.yml`** is git-tracked. Password fields **must not** contain literal secret values — they must be left as placeholder strings (e.g., `"CHANGE_ME"`) and overridden at runtime via environment variables. The config loader performs its own env-var interpolation on any value matching `${VAR_NAME}` or `${VAR_NAME:-default}` syntax before YAML parsing completes.

```yaml
nacos:
  serverAddr: 127.0.0.1:8848
  username: nacos
  password: ${NACOS_PASSWORD}          # must come from env var
  defaultNamespace: dev

datasource:
  url: jdbc:mysql://localhost:3306/nacosbase
  username: root
  password: ${DB_PASSWORD}             # must come from env var

changelog:
  scriptsDir: ./changelogs
  appliedBy: ${USER:-nacosbase}        # env var with fallback
```

**Environment variable overrides (higher priority than YAML values):**

| Env Var | Config key |
|---------|-----------|
| `NACOS_SERVER_ADDR` | `nacos.serverAddr` |
| `NACOS_USERNAME` | `nacos.username` |
| `NACOS_PASSWORD` | `nacos.password` |
| `DB_URL` | `datasource.url` |
| `DB_USERNAME` | `datasource.username` |
| `DB_PASSWORD` | `datasource.password` |

---

## Dependency Selections

| Purpose | Library |
|---------|---------|
| CLI parsing | `clikt` |
| YAML config | `kaml` + `kotlinx.serialization` |
| CSV parsing | `kotlin-csv` |
| Nacos connection | `nacos-client` 2.x |
| DB access | `Exposed` ORM + `mysql-connector-j` |
| Checksum | JDK built-in `MessageDigest` (SHA-256) |
| Testing (unit) | `kotlin.test` + JUnit 5 + hand-written fakes |
| Testing (infra) | Testcontainers (MySQL + Nacos) |

---

## Error Handling

All public engine methods return `Result<T>`. The CLI layer maps `Result.failure` to a non-zero exit code with a human-readable message. No exceptions are used for control flow.

---

## Testing Strategy

- **`nacosbase-core`**: Pure unit tests with in-memory fakes for all ports. No mocking framework. Covers all engine logic, idempotency rules, validation, and rollback ordering.
- **`nacosbase-infra`**: Integration tests using Testcontainers (MySQL + Nacos) for all adapters. Covers the lock mechanism, checksum persistence, and CSV loading rules.
- **`nacosbase-cli`**: Command-level tests using Clikt's built-in test runner. Covers exit codes (including `diff` exit 1 on pending changes) and error message formatting.
- Target coverage: 80%+
