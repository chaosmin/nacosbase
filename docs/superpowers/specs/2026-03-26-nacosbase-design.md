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
enum class ConfigType { YAML, PROPERTIES, JSON, TEXT }
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
    val content: String?,   // null for DELETE
    val type: ConfigType?,
    val description: String?,
)

data class ChangeScript(
    val scriptName: String,   // e.g. "001-init-redis.csv"
    val checksum: String,     // SHA-256 of file content
    val changeSets: List<ChangeSet>,
)

data class ChangeRecord(
    val scriptName: String,
    val checksum: String,
    val appliedAt: Instant,
    val appliedBy: String,
    val executionMs: Long,
    val status: ExecutionStatus,
    val rollbackData: String?,  // JSON snapshot of pre-change state
)
```

### Port Interfaces (`nacosbase-core`)

```kotlin
interface NacosPort {
    fun fetchAll(namespace: String): List<NacosConfig>
    fun publish(config: NacosConfig)
    fun delete(dataId: String, group: String, namespace: String)
}

interface ChangeLogPort {
    fun findApplied(): List<ChangeRecord>
    fun markApplied(record: ChangeRecord)
    fun markRolledBack(scriptName: String)
}

interface ScriptLoaderPort {
    fun loadOrdered(scriptsDir: Path): List<ChangeScript>
}
```

---

## CSV Change Script Format

**File naming:** `{序号}-{描述}.csv` — executed in ascending numeric order.

```csv
action,dataId,group,namespace,content,type,description
ADD,redis.yml,DEFAULT_GROUP,dev,"server: redis\nport: 6379",YAML,初始化Redis配置
MODIFY,app.properties,DEFAULT_GROUP,dev,"timeout=3000",PROPERTIES,调整超时
DELETE,old-config.yml,DEFAULT_GROUP,dev,,YAML,清理废弃配置
```

**Action semantics:**
- `ADD` — create new DataID; error if already exists
- `MODIFY` — overwrite existing DataID content; error if not found
- `DELETE` — remove DataID; error if not found; `content` column is empty
- Rollback restores pre-change snapshots stored in `rollback_data` (JSON)

---

## Execution Log (MySQL)

```sql
CREATE TABLE nacosbase_changelog (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_name   VARCHAR(255) NOT NULL UNIQUE,
    checksum      VARCHAR(64)  NOT NULL,
    applied_at    DATETIME     NOT NULL,
    applied_by    VARCHAR(100),
    execution_ms  BIGINT,
    status        VARCHAR(20)  NOT NULL,        -- SUCCESS | FAILED | ROLLED_BACK
    description   VARCHAR(500),
    rollback_data JSON                           -- pre-change snapshot for rollback
);
```

**Idempotency rules:**
- Script already applied with matching checksum → skip silently
- Script already applied with mismatched checksum → halt with error (tamper detected)
- Script not in log → apply and record

---

## CLI Commands

Built with [Clikt](https://ajalt.github.io/clikt/).

| Command | Description |
|---------|-------------|
| `baseline` | Export current Nacos configs as a baseline CSV file |
| `update` | Apply all pending change scripts in order |
| `status` | List all scripts: applied / pending / checksum mismatch |
| `diff` | Preview what `update` would do without touching Nacos |
| `validate` | Validate CSV format only, no Nacos or DB connection needed |
| `rollback` | Revert the last N applied scripts (default: 1) |

```bash
nacosbase baseline --output ./changelogs/000-baseline.csv
nacosbase diff     --scripts ./changelogs/
nacosbase update   --scripts ./changelogs/
nacosbase status
nacosbase rollback --count 2
nacosbase validate --scripts ./changelogs/
```

---

## Configuration

**`nacosbase.yml`** (project root, git-tracked without secrets):

```yaml
nacos:
  serverAddr: 127.0.0.1:8848
  username: nacos
  password: nacos
  defaultNamespace: dev

datasource:
  url: jdbc:mysql://localhost:3306/nacosbase
  username: root
  password: secret

changelog:
  scriptsDir: ./changelogs
  appliedBy: ${USER:-nacosbase}
```

**Environment variable overrides (higher priority):**

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
| Testing | `kotlin.test` + JUnit 5 + hand-written fakes |

---

## Error Handling

All public engine methods return `Result<T>`. The CLI layer maps `Result.failure` to a non-zero exit code with a human-readable message. No exceptions are used for control flow.

---

## Testing Strategy

- **`nacosbase-core`**: Pure unit tests with in-memory fakes for all ports. No mocking framework.
- **`nacosbase-infra`**: Integration tests against a real MySQL (Testcontainers) and Nacos (Testcontainers or a local instance).
- **`nacosbase-cli`**: Command-level tests using Clikt's test runner.
- Target coverage: 80%+
