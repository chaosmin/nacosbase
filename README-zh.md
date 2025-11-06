[English](README.md) | 简体中文

# nacosbase

本项目以 Gradle + Kotlin 作为主要构建方式。

## 模块
- `nacosbase-core`：核心库

## 构建与测试
- 推荐使用 Gradle Wrapper：
  - Windows：
    - 构建：`./gradlew.bat build`
    - 仅测试：`./gradlew.bat test`
  - macOS/Linux：
    - 构建：`./gradlew build`
    - 仅测试：`./gradlew test`

## 环境要求
- JDK 17+
- 仓库已提交 `gradle/wrapper/**`，可直接用 Wrapper 构建；如需也可使用本地 Gradle。

## 说明
- CLI 模块已从构建中移除；后续计划的远程控制 Bash 脚本可放在 `scripts/` 目录。
