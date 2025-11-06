English | [简体中文](README-zh.md)

# nacosbase

Project scaffolding using Gradle + Kotlin as the primary build system.

## Modules
- `nacosbase-core`: Core library

## Build & Test
- Using Gradle Wrapper (recommended):
  - Windows:
    - Build: `./gradlew.bat build`
    - Test only: `./gradlew.bat test`
  - macOS/Linux:
    - Build: `./gradlew build`
    - Test only: `./gradlew test`

## Requirements
- JDK 17+
- Gradle Wrapper is checked in under `gradle/wrapper/**`. You can also use a local Gradle installation if needed.

## Notes
- The CLI module has been removed from the build. Future shell scripts for remote control can live under `scripts/`.
