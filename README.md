English | [简体中文](README-zh.md)

# nacosbase

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
action,dataId,group,namespace,content,type,description
ADD,redis.yml,DEFAULT_GROUP,dev,"host: localhost
port: 6379
",YAML,Add Redis configuration
```

Supported actions: `ADD`, `MODIFY`, `DELETE`.

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
| `action` | Yes | `ADD`, `MODIFY`, or `DELETE` |
| `dataId` | Yes | Nacos DataID |
| `group` | Yes | Nacos group (e.g. `DEFAULT_GROUP`) |
| `namespace` | Yes | Nacos namespace ID |
| `content` | For ADD/MODIFY | Configuration content (quote multi-line values) |
| `type` | For ADD/MODIFY | `YAML`, `PROPERTIES`, `JSON`, `TEXT`, `TOML` |
| `description` | No | Human-readable description |

Script filenames must follow the pattern `{N}-{description}.csv` where `N` is a positive integer. Duplicate prefixes are rejected by `validate`.

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
