# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`nacosbase` is a Nacos configuration version management tool inspired by [Liquibase](https://www.liquibase.org/). It brings database-migration-style discipline to Nacos config management: baseline generation, CSV-based change scripts, execution tracking with idempotency, format validation, multi-environment promotion, and rollback.

**Core concept mapping:**

| Liquibase | nacosbase |
|-----------|-----------|
| ChangeSet | CSV change script |
| ChangeLog | Ordered collection of change scripts |
| DATABASECHANGELOG | Execution log (tracks applied scripts + checksums) |
| Checksum validation | Tamper detection for applied scripts |

Built with Kotlin JVM, Gradle 9.2, JDK 21.

## Build & Test Commands

Use the Gradle wrapper (no local Gradle installation required):

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run tests for a specific module
./gradlew :nacosbase-core:test
./gradlew :nacosbase-infra:test
./gradlew :nacosbase-cli:test

# Run a single test class
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.engine.ChangeEngineTest"

# Compile only (no tests)
./gradlew compileKotlin

# Build fat-jar CLI
./gradlew :nacosbase-cli:shadowJar
# Output: nacosbase-cli/build/libs/nacosbase-0.1.0.jar
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Architecture

### Module Structure (strict one-way dependency chain)

```
nacosbase-cli → nacosbase-infra → nacosbase-core
```

- **`nacosbase-core`** — Pure Kotlin domain layer: models, port interfaces, `ChangeEngine`, `ConfigValidator`. Zero IO dependencies.
- **`nacosbase-infra`** — Infrastructure adapters: `NacosClientAdapter` (Nacos SDK), `MysqlChangeLogAdapter` (Exposed ORM), `CsvScriptLoader` (kotlin-csv), `ConfigLoader` (kaml).
- **`nacosbase-cli`** — CLI fat-jar (Clikt): six commands (`baseline`, `update`, `status`, `diff`, `validate`, `rollback`). Assembles layers via `AppContext`.

### Key Domain Concepts

- **Baseline**: Snapshot of all current Nacos configs, exported as a CSV with all `ADD` rows.
- **Change script (CSV)**: Files named `{N}-{description}.csv`, sorted and executed by numeric prefix ASC.
- **Execution log**: MySQL `nacosbase_changelog` table tracks applied scripts with checksums; `nacosbase_lock` table provides distributed locking.
- **Idempotency**: SUCCESS+matching checksum → skip; SUCCESS+mismatch → halt (tamper detected); FAILED → retry; ROLLED_BACK → re-apply.

### Configuration

`nacosbase.yml` is the config file (git-tracked). Passwords must use `${ENV_VAR}` syntax — never literal values. The config loader interpolates env vars before YAML parsing.

### Testing

- `nacosbase-core`: Pure unit tests with in-memory fakes in `com.nacosbase.core.fake`.
- `nacosbase-infra`: Testcontainers integration tests (MySQL). Tests skip automatically if Docker is unavailable.
- `nacosbase-cli`: Clikt test runner. Commands accept an `engineFactory` constructor param for injecting fake engines in tests.
