[English](README.md) | 简体中文

# nacosbase

**nacosbase** 是一款参考 [Liquibase](https://www.liquibase.org/) 设计理念的 Nacos 配置版本管理工具。它将数据库迁移式的工作流引入 Nacos 配置管理，为团队提供可重复、可审计、可脚本化的配置变更能力，适用于多环境场景。

## 核心功能

### 1. 基线生成
连接运行中的 Nacos 实例，将当前所有配置导出为版本化的基线快照，作为后续跟踪配置变更的起点。

### 2. 格式校验与排序
对配置内容进行格式校验（支持 YAML、Properties、JSON、TOML），并应用规范化排序规则，确保多环境配置一致性，减少无意义的 diff 噪音。

### 3. CSV 变更脚本
通过结构化 CSV 脚本定义配置变更，支持以下操作：
- **新增** — 创建新的配置项或 DataID
- **修改** — 更新已有配置的值
- **删除** — 移除配置项或 DataID
- **回滚** — 将某个变更集还原至变更前的状态

CSV 脚本对人类友好且对版本控制友好，类似于 Liquibase 的 changelog 文件。

### 4. 执行记录与幂等性
每个变更脚本均会计算校验和并记录到持久化执行日志中（参考 Liquibase 的 `DATABASECHANGELOG` 表设计）。重复执行工具时，已应用的脚本会被跳过；若脚本被篡改，工具将通过校验和不匹配检测到异常，保证部署的安全性与可重复性。

### 5. 多环境支持
按 Nacos 命名空间（dev / test / staging / prod）管理独立的变更集，支持配置晋升流程，将已验证的配置向上游环境推送。

### 6. Diff 与预演（Dry Run）
对比当前 Nacos 状态与变更脚本期望状态，在正式执行前预览将要应用的变更内容。

### 7. 回滚
回滚一个或多个已应用的变更集，恢复执行日志中记录的历史配置值。

## 模块

| 模块 | 说明 |
|------|------|
| `nacosbase-core` | 核心库：Nacos 客户端、变更集引擎、执行日志、格式校验 |

## 构建与测试

推荐使用 Gradle Wrapper（无需本地安装 Gradle）：

```bash
# 构建
./gradlew build          # macOS/Linux
./gradlew.bat build      # Windows

# 测试
./gradlew test
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.*"

# 清理并重新构建
./gradlew clean build
```

## 环境要求

- JDK 17+
- Nacos Server（兼容 Nacos 2.x）
- 仓库已提交 `gradle/wrapper/`，可直接使用 Gradle Wrapper

## 设计参考

nacosbase 的核心概念大量借鉴自 [Liquibase](https://www.liquibase.org/)：

| Liquibase 概念 | nacosbase 对应概念 |
|---|---|
| ChangeSet | CSV 变更脚本 |
| ChangeLog | 有序变更脚本集合 |
| DATABASECHANGELOG | nacosbase 执行日志 |
| 校验和验证 | 已应用脚本的篡改检测 |
