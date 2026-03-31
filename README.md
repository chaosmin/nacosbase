English | [简体中文](README-zh.md)

# nacosbase

[![GitHub Release](https://img.shields.io/github/v/release/chaosmin/nacosbase?style=flat-square&color=6366f1)](https://github.com/chaosmin/nacosbase/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/chaosmin/nacosbase/release.yml?style=flat-square&label=build)](https://github.com/chaosmin/nacosbase/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-21+-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![License](https://img.shields.io/github/license/chaosmin/nacosbase?style=flat-square&color=22c55e)](LICENSE)

**nacosbase** is a Nacos configuration version management tool inspired by [Liquibase](https://www.liquibase.org/). It brings database-migration-style discipline to Nacos config management — giving teams a reproducible, auditable, and scriptable workflow for managing configurations across environments.

## Core Concepts

| Liquibase | nacosbase |
|-----------|-----------|
| ChangeSet | CSV change script |
| ChangeLog | Ordered collection of change scripts |
| DATABASECHANGELOG | `nacosbase_changelog` MySQL table |
| Checksum validation | Tamper detection for applied scripts |

## Quick Start

### 1. Prerequisites

- JDK 21+
- MySQL 8.x (tracks applied change scripts)
- Nacos Server 2.x

### 2. Build the CLI fat-jar

```bash
./gradlew :nacosbase-cli:shadowJar          # macOS/Linux
gradlew.bat :nacosbase-cli:shadowJar        # Windows
# Output: nacosbase-cli/build/libs/nacosbase-0.1.0.jar
```

### 3. Create `nacosbase.yml`

```yaml
nacos:
  serverAddr: 127.0.0.1:8848
  username: nacos
  password: ${NACOS_PASSWORD}           # from env var

datasource:
  url: jdbc:mysql://localhost:3306/nacosbase
  username: root
  password: ${DB_PASSWORD}              # from env var

changelog:
  scriptsDir: ./changelogs              # directory containing CSV scripts
  appliedBy: ${USER:-nacosbase}         # recorded in the execution log
```

Passwords **must** be provided via environment variables (`${VAR}` or `${VAR:-default}`). Never hardcode credentials.

### 4. Export the current baseline

```bash
java -jar nacosbase-0.1.0.jar baseline \
  --namespace dev \
  --output changelogs/000-baseline.csv
```

This connects to Nacos and generates a CSV snapshot of every config in `namespace=dev`.

### 5. Write change scripts

Create numbered CSV files inside `scriptsDir` (e.g. `changelogs/`). Files are applied in ascending numeric-prefix order.

**`changelogs/001-add-redis.csv`**

```csv
action,dataId,group,namespace,key,value,type,description,operator
ADD,redis.yml,DEFAULT_GROUP,dev,host,localhost,YAML,Add Redis config,hugo
ADD,redis.yml,DEFAULT_GROUP,dev,port,6379,YAML,Add Redis config,hugo
```

Multiple rows with the same `(action, dataId, group, namespace)` are grouped into a single changeset. The example above adds two keys to `redis.yml` in one atomic operation.

Supported actions and their key-level semantics:

| Action | Behaviour |
|--------|-----------|
| `ADD` | If the config does **not** exist, create it with the given keys. If it already exists, **merge** the new keys in — fails if any key is already present. |
| `MODIFY` | Update the specified keys in an existing config — fails if the config or any key does not exist. Unspecified keys are preserved unchanged. |
| `DELETE` | If `value` is blank: remove the entire key. If `value` is set: remove that specific item from the key's list — fails if the key is not a list or the value is not present. Omit both `key` and `value` to delete the whole config. Fails if the config or key does not exist. |
| `APPEND` | Append `value` to the list at `key`. If the current value is a scalar it is promoted to a list. Fails if the key holds a nested object. Each row is an independent operation (rows are **not** merged). |

**Pre-execution validation:** before applying any changeset, nacosbase validates every operation in the CSV against the current Nacos state. If any condition cannot be satisfied, the entire script is aborted and all errors are reported at once:

```
Validation failed for '001-add-redis.csv':
[1] host existed
[2] port existed
```

### 6. Preview changes (dry run)

```bash
java -jar nacosbase-0.1.0.jar diff --config nacosbase.yml
```

Exits `0` if Nacos matches scripts exactly, `1` if changes would be applied. Example output:

```
[ADD]     redis.yml @ DEFAULT_GROUP/dev
[MODIFY]  app.yml @ DEFAULT_GROUP/prod
```

### 7. Apply changes

```bash
java -jar nacosbase-0.1.0.jar update --config nacosbase.yml
```

Already-applied scripts are skipped. A checksum mismatch on an applied script halts execution immediately.

### 8. Check status

```bash
java -jar nacosbase-0.1.0.jar status --config nacosbase.yml
```

Lists every script with its status (`SUCCESS`, `FAILED`, `ROLLED_BACK`) and when it was applied.

### 9. Roll back

```bash
# Roll back the last applied script (default)
java -jar nacosbase-0.1.0.jar rollback --config nacosbase.yml

# Roll back the last N scripts
java -jar nacosbase-0.1.0.jar rollback --count 3 --config nacosbase.yml
```

### 10. Validate scripts offline

```bash
java -jar nacosbase-0.1.0.jar validate --scripts ./changelogs
```

Checks CSV structure and duplicate numeric prefixes without connecting to Nacos or MySQL. Exits `0` on success.

## CLI Reference

```
Usage: nacosbase [command] [options]

Commands:
  baseline   Export current Nacos configs as a baseline CSV
  update     Apply pending change scripts to Nacos
  status     Show execution log (applied scripts and their status)
  diff       Preview changes that would be applied by update
  validate   Validate CSV change scripts offline
  rollback   Roll back one or more applied change scripts

Global options (per command):
  --config   Path to nacosbase.yml  (default: nacosbase.yml)
```

## CSV Script Format

| Column | Required | Description |
|--------|----------|-------------|
| `action` | Yes | `ADD`, `MODIFY`, `DELETE`, or `APPEND` |
| `dataId` | Yes | Nacos DataID |
| `group` | Yes | Nacos group (e.g. `DEFAULT_GROUP`) |
| `namespace` | Yes | Nacos namespace name or ID |
| `key` | For ADD/MODIFY/DELETE/APPEND | Dot-notation key path (e.g. `server.port`). Leave blank to delete the whole config (DELETE only). |
| `value` | For ADD/MODIFY/APPEND; optional for DELETE | Scalar value. For DELETE: if set, removes that specific item from the key's list instead of the whole key. |
| `type` | For ADD/MODIFY/APPEND | `YAML`, `PROPERTIES`, `JSON`, or `TEXT` |
| `description` | No | Human-readable change description |
| `operator` | No | Who is making this change |

Script filenames must follow the pattern `{N}-{description}.csv` where `N` is a positive integer. Duplicate prefixes are rejected by `validate`.

### Nested structure support

nacosbase preserves the original nested format of YAML and JSON configs. Dot-notation keys are automatically expanded to nested structures:

```csv
ADD,app.yml,DEFAULT_GROUP,dev,server.host,localhost,YAML,init,hugo
ADD,app.yml,DEFAULT_GROUP,dev,server.port,8080,YAML,init,hugo
```

Results in:

```yaml
server:
  host: localhost
  port: 8080
```

YAML lists (sequences of objects) are also preserved through the flatten/assemble round-trip:

```yaml
roles:
  - roleName: PAYER
    accountType: EPS
  - roleName: LABEL_OWNER
    accountType: EPS
```

### APPEND and DELETE list-value examples

```csv
# Append items to a list key (each row is independent)
APPEND,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,add server,hugo
APPEND,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.2,YAML,add server,hugo

# Delete a specific item from a list
DELETE,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,remove server,hugo

# Delete an entire key (value left blank)
DELETE,app.yml,DEFAULT_GROUP,dev,timeout,,YAML,remove key,hugo

# Delete the whole config (key and value both blank)
DELETE,app.yml,DEFAULT_GROUP,dev,,,YAML,remove config,hugo
```

## Modules

```
nacosbase-cli → nacosbase-infra → nacosbase-core
```

| Module | Description |
|--------|-------------|
| `nacosbase-core` | Pure domain layer: models, port interfaces, `ChangeEngine`, `ConfigValidator` |
| `nacosbase-infra` | Adapters: Nacos SDK, MySQL/Exposed ORM, CSV loader, YAML config loader |
| `nacosbase-cli` | CLI fat-jar (Clikt): six commands assembled via `AppContext` |

## Build & Test

```bash
# Build all modules
./gradlew build

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :nacosbase-core:test
./gradlew :nacosbase-infra:test      # requires Docker for Testcontainers (MySQL)
./gradlew :nacosbase-cli:test

# Run a single test class
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.engine.ChangeEngineTest"

# Build CLI fat-jar
./gradlew :nacosbase-cli:shadowJar
```

> **Note:** `nacosbase-infra` integration tests require Docker. Tests are automatically skipped when Docker is unavailable.

## Inspiration

nacosbase draws heavily from [Liquibase](https://www.liquibase.org/). If you have used Liquibase for database migrations, the mental model transfers directly: write numbered change scripts, run `update` to apply them, and use `rollback` to revert.
