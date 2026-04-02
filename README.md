English | [简体中文](README-zh.md)

# nacosbase

[![GitHub Release](https://img.shields.io/github/v/release/chaosmin/nacosbase?style=flat-square&color=6366f1)](https://github.com/chaosmin/nacosbase/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/chaosmin/nacosbase/release.yml?style=flat-square&label=build)](https://github.com/chaosmin/nacosbase/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-21+-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![codecov](https://codecov.io/gh/chaosmin/nacosbase/branch/master/graph/badge.svg?token=HmsHWVDgSa)](https://codecov.io/gh/chaosmin/nacosbase)
[![License](https://img.shields.io/github/license/chaosmin/nacosbase?style=flat-square&color=22c55e)](LICENSE)

**nacosbase** is a Nacos configuration version management tool inspired by [Liquibase](https://www.liquibase.org/). Write numbered CSV change scripts, run `update` to apply them, use `rollback` to revert — the same mental model as database migrations, applied to Nacos configs.

| Liquibase | nacosbase |
|-----------|-----------|
| ChangeSet | CSV change script |
| ChangeLog | Ordered collection of change scripts |
| DATABASECHANGELOG | `nacosbase_changelog` MySQL table |
| Checksum validation | Tamper detection for applied scripts |

## Prerequisites

- JDK 21+
- MySQL 8.x
- Nacos Server 2.x

## Quick Start

### 1. Build

```bash
./gradlew :nacosbase-cli:shadowJar
# Output: nacosbase-cli/build/libs/nacosbase-0.1.0.jar
```

### 2. Configure

```yaml
# nacosbase.yml
nacos:
  serverAddr: 127.0.0.1:8848
  username: nacos
  password: ${NACOS_PASSWORD}

datasource:
  url: jdbc:mysql://localhost:3306/nacosbase
  username: root
  password: ${DB_PASSWORD}

changelog:
  scriptsDir: ./changelogs
  appliedBy: ${USER:-nacosbase}
```

Passwords **must** use `${ENV_VAR}` syntax — never hardcode credentials.

### 3. Export baseline

```bash
java -jar nacosbase-0.1.0.jar baseline --namespace dev --output changelogs/000-baseline.csv
```

### 4. Write change scripts

Create `changelogs/{N}-{description}.csv` files. Files execute in ascending numeric-prefix order.

```csv
action,dataId,group,namespace,key,value,type,description,operator
ADD,redis.yml,DEFAULT_GROUP,dev,host,localhost,YAML,Add Redis,hugo
ADD,redis.yml,DEFAULT_GROUP,dev,port,6379,YAML,Add Redis,hugo
```

Rows sharing the same `(action, dataId, group, namespace)` are merged into one atomic changeset.

### 5. Apply

```bash
java -jar nacosbase-0.1.0.jar diff    --config nacosbase.yml   # preview
java -jar nacosbase-0.1.0.jar update  --config nacosbase.yml   # apply
java -jar nacosbase-0.1.0.jar status  --config nacosbase.yml   # inspect log
java -jar nacosbase-0.1.0.jar rollback --count 1 --config nacosbase.yml  # revert
```

## CSV Format

| Column | Required | Description |
|--------|----------|-------------|
| `action` | Yes | `ADD`, `MODIFY`, `DELETE`, `APPEND` |
| `dataId` | Yes | Nacos DataID |
| `group` | Yes | Nacos group |
| `namespace` | Yes | Nacos namespace name or ID |
| `key` | Varies | Dot-notation path (e.g. `server.port`). Omit to delete the whole config (`DELETE` only). |
| `value` | Varies | Scalar value. For `DELETE`: removes a specific list item when set; removes the whole key when blank. |
| `type` | ADD/MODIFY/APPEND | `YAML`, `PROPERTIES`, `JSON`, `TEXT` |
| `description` | No | Audit note |
| `operator` | No | Who made the change |

### Action semantics

| Action | Behaviour |
|--------|-----------|
| `ADD` | Creates config if absent; merges new keys if config exists. Fails on key conflicts. |
| `MODIFY` | Updates specified keys in an existing config. Unspecified keys are preserved. Fails if config or key is absent. |
| `DELETE` | No key/value → delete whole config. Key only → delete that key. Key + value → remove list item. Fails if target not found. |
| `APPEND` | Appends value to a list key; promotes scalar to list if needed. Each row is independent (never merged). |

**Pre-execution validation:** every changeset is validated against live Nacos state before anything is applied. All errors are collected and reported at once.

### Examples

```csv
# Nested keys expand to YAML structure
ADD,app.yml,DEFAULT_GROUP,dev,server.host,localhost,YAML,init,hugo
ADD,app.yml,DEFAULT_GROUP,dev,server.port,8080,YAML,init,hugo

# Append to / remove from a list
APPEND,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,add,hugo
DELETE,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,remove,hugo

# Delete a key, or the whole config
DELETE,app.yml,DEFAULT_GROUP,dev,timeout,,YAML,drop key,hugo
DELETE,app.yml,DEFAULT_GROUP,dev,,,YAML,drop config,hugo
```

## Execution Log

Applied scripts are tracked in two MySQL tables, created automatically on first run.

**`nacosbase_changelog`** — one row per script:

| Column | Description |
|--------|-------------|
| `script_name` | CSV filename (unique key) |
| `checksum` | SHA-256 of file content for tamper detection |
| `status` | `SUCCESS` / `FAILED` / `ROLLED_BACK` |
| `applied_at` / `applied_by` / `execution_ms` | When, who, how long |
| `rollback_data` | JSON snapshot of pre-change configs |
| `rolled_back_at` | Set when rolled back |

**`nacosbase_changelog_item`** — one row per changeset within a script:

| Column | Description |
|--------|-------------|
| `changelog_id` | FK → `nacosbase_changelog.id` |
| `action` | `ADD` / `MODIFY` / `DELETE` / `APPEND` |
| `data_id` / `config_group` / `namespace` | Target config coordinates |
| `content` / `type` | Assembled content and format |
| `description` / `operator` / `target_key` | Audit metadata |

Items are replaced atomically with their parent record on every upsert.

## Modules

```
nacosbase-cli → nacosbase-infra → nacosbase-core
```

| Module | Description |
|--------|-------------|
| `nacosbase-core` | Domain layer: models, port interfaces, `ChangeEngine`, `ConfigValidator` |
| `nacosbase-infra` | Adapters: Nacos SDK, MySQL/Exposed ORM, CSV loader, YAML config loader |
| `nacosbase-cli` | CLI fat-jar (Clikt): six commands assembled via `AppContext` |

## Build & Test

```bash
./gradlew build
./gradlew test
./gradlew :nacosbase-infra:test      # requires Docker (Testcontainers + MySQL)
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.engine.ChangeEngineTest"
```

> `nacosbase-infra` integration tests require Docker and skip automatically when unavailable.
