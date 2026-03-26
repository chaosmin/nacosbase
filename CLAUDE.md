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

Built with Kotlin JVM, Gradle 9.2, JDK 21. Currently contains one module: `nacosbase-core`.

## Build & Test Commands

Use the Gradle wrapper (no local Gradle installation required):

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run tests for a specific module
./gradlew :nacosbase-core:test

# Run a single test class
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.VersionTest"

# Clean and rebuild
./gradlew clean build

# Compile only (no tests)
./gradlew compileKotlin
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Architecture

### Module Structure

```
nacosbase/                        # Root multi-project build
├── nacosbase-core/               # Core library: Nacos client, change set engine,
│   └── src/                      # execution log, format validation
│       ├── main/kotlin/com/nacosbase/core/
│       └── test/kotlin/com/nacosbase/core/
└── settings.gradle.kts           # Module declarations
```

### Key Domain Concepts

- **Baseline**: Snapshot of all current Nacos configs, exported as the starting point.
- **Change script (CSV)**: Human-readable file declaring add/modify/delete operations on Nacos DataIDs.
- **Execution log**: Persistent record of applied scripts with checksums; enables idempotency and tamper detection.
- **Namespace**: Nacos namespace maps to an environment (dev/test/staging/prod); change sets are scoped per namespace.

### Build Configuration

- **Root `build.gradle.kts`**: Applies the Kotlin JVM plugin and MavenCentral repository to all subprojects.
- **Module `build.gradle.kts`**: JVM toolchain targets JDK 21, tests use JUnit Platform.
- **`gradle.properties`**: Daemon enabled, parallel builds, build caching, and configuration cache all enabled.

### Adding New Modules

1. Create the module directory with a `build.gradle.kts`.
2. Register it in `settings.gradle.kts` with `include("new-module-name")`.
3. Plugins and MavenCentral are inherited from the root build — no need to redeclare.

### Package Conventions

All source code uses reverse domain naming under `com.nacosbase.*`. Module-specific packages live under `com.nacosbase.<module-name>`.

### Testing

Tests use `kotlin("test")` which provides JUnit 5 assertions and the JUnit Platform runner. Test source mirrors the main source package structure.
