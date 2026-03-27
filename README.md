English | [简体中文](README-zh.md)

# nacosbase

**nacosbase** is a Nacos configuration version management tool inspired by [Liquibase](https://www.liquibase.org/). It brings database-migration-style discipline to Nacos config management — giving teams a reproducible, auditable, and scriptable workflow for managing configurations across environments.

## Core Features

### 1. Baseline Generation
Connect to a running Nacos instance and export all current configurations as a versioned baseline snapshot. This serves as the starting point for tracking configuration drift.

### 2. Format Validation & Sorting
Validate configuration content against expected formats (YAML, Properties, JSON, TOML) and apply canonical sorting rules, ensuring consistency across environments and reducing noisy diffs.

### 3. CSV-Based Change Scripts
Define configuration changes in structured CSV scripts, supporting:
- **Add** — create new configuration items or entire DataIDs
- **Modify** — update existing values
- **Delete** — remove configuration items or DataIDs
- **Rollback** — revert a change set to a previous state

CSV scripts are human-readable and version-control-friendly, similar to Liquibase changelogs.

### 4. Execution Tracking & Idempotency
Every change script is checksummed and recorded in a persistent execution log (modeled after Liquibase's `DATABASECHANGELOG` table). Re-running the tool skips already-applied scripts and detects tampering via checksum mismatch — guaranteeing safe, repeatable deployments.

### 5. Multi-Environment Support
Separate change sets per Nacos namespace (dev / test / staging / prod), with promotion workflows to propagate verified configurations upstream.

### 6. Diff & Dry Run
Compare the current Nacos state against the expected state from scripts and preview what changes would be applied before execution.

### 7. Rollback
Roll back one or more applied change sets, restoring previous configuration values recorded in the execution log.

## Modules

| Module | Description |
|--------|-------------|
| `nacosbase-core` | Core library: Nacos client, change set engine, execution log, format validation |

## Build & Test

Using Gradle Wrapper (no local Gradle installation required):

```bash
# Build
./gradlew build          # macOS/Linux
./gradlew.bat build      # Windows

# Test
./gradlew test
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.*"

# Clean build
./gradlew clean build
```

## Requirements

- JDK 17+
- Nacos Server (compatible with Nacos 2.x)
- Gradle Wrapper is checked in under `gradle/wrapper/`

## Inspiration

nacosbase draws heavily from [Liquibase](https://www.liquibase.org/) concepts:
- **ChangeSet** → CSV change script
- **ChangeLog** → ordered collection of change scripts
- **DATABASECHANGELOG** → nacosbase execution log
- **Checksum validation** → tamper detection for applied scripts
