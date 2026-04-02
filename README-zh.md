[English](README.md) | 简体中文

# nacosbase

[![GitHub Release](https://img.shields.io/github/v/release/chaosmin/nacosbase?style=flat-square&color=6366f1)](https://github.com/chaosmin/nacosbase/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/chaosmin/nacosbase/release.yml?style=flat-square&label=build)](https://github.com/chaosmin/nacosbase/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-21+-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![codecov](https://codecov.io/gh/chaosmin/nacosbase/branch/master/graph/badge.svg?token=HmsHWVDgSa)](https://codecov.io/gh/chaosmin/nacosbase)
[![License](https://img.shields.io/github/license/chaosmin/nacosbase?style=flat-square&color=22c55e)](LICENSE)

**nacosbase** 是一款参考 [Liquibase](https://www.liquibase.org/) 设计的 Nacos 配置版本管理工具。编写带编号的 CSV 变更脚本，执行 `update` 应用变更，使用 `rollback` 回退——与数据库迁移完全相同的心智模型，用于管理 Nacos 配置。

| Liquibase | nacosbase |
|-----------|-----------|
| ChangeSet | CSV 变更脚本 |
| ChangeLog | 有序变更脚本集合 |
| DATABASECHANGELOG | `nacosbase_changelog` MySQL 表 |
| 校验和验证 | 已应用脚本的篡改检测 |

## 环境要求

- JDK 21+
- MySQL 8.x
- Nacos Server 2.x

## 快速开始

### 1. 构建

```bash
./gradlew :nacosbase-cli:shadowJar
# 输出：nacosbase-cli/build/libs/nacosbase-0.1.0.jar
```

### 2. 配置

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

密码**必须**通过 `${ENV_VAR}` 引用环境变量，禁止在配置文件中硬编码凭据。

### 3. 导出基线

```bash
java -jar nacosbase-0.1.0.jar baseline --namespace dev --output changelogs/000-baseline.csv
```

### 4. 编写变更脚本

在 `changelogs/` 目录下创建 `{N}-{描述}.csv` 文件，脚本按数字前缀升序执行。

```csv
action,dataId,group,namespace,key,value,type,description,operator
ADD,redis.yml,DEFAULT_GROUP,dev,host,localhost,YAML,添加 Redis,hugo
ADD,redis.yml,DEFAULT_GROUP,dev,port,6379,YAML,添加 Redis,hugo
```

`(action, dataId, group, namespace)` 相同的多行会合并为一个原子变更集。

### 5. 执行

```bash
java -jar nacosbase-0.1.0.jar diff    --config nacosbase.yml        # 预览变更
java -jar nacosbase-0.1.0.jar update  --config nacosbase.yml        # 应用变更
java -jar nacosbase-0.1.0.jar status  --config nacosbase.yml        # 查看执行日志
java -jar nacosbase-0.1.0.jar rollback --count 1 --config nacosbase.yml  # 回滚
```

## CSV 格式

| 列名 | 是否必填 | 说明 |
|------|----------|------|
| `action` | 是 | `ADD`、`MODIFY`、`DELETE`、`APPEND` |
| `dataId` | 是 | Nacos DataID |
| `group` | 是 | Nacos group |
| `namespace` | 是 | Nacos 命名空间名称或 ID |
| `key` | 视操作而定 | 点分隔 key 路径（如 `server.port`）；`DELETE` 整个配置时可省略 |
| `value` | 视操作而定 | 标量值；`DELETE` 时填写则删除 list 中的指定项，留空则删除整个 key |
| `type` | ADD/MODIFY/APPEND | `YAML`、`PROPERTIES`、`JSON`、`TEXT` |
| `description` | 否 | 变更描述 |
| `operator` | 否 | 操作人 |

### 操作语义

| 操作 | 行为 |
|------|------|
| `ADD` | 配置不存在则创建；已存在则合并新 key，key 冲突时报错 |
| `MODIFY` | 更新指定 key，未涉及的 key 保持不变；配置或 key 不存在时报错 |
| `DELETE` | key/value 均空 → 删整个配置；仅 key → 删该 key；key+value → 删 list 中的指定项；目标不存在时报错 |
| `APPEND` | 向 list key 追加值，标量自动升级为 list；每行独立执行（不合并） |

**执行前校验：** 应用前对所有变更集与 Nacos 当前状态进行全量校验，存在任何不满足的条件则中止整个脚本，并一次性输出全部错误。

### 示例

```csv
# 点分隔 key 自动展开为嵌套结构
ADD,app.yml,DEFAULT_GROUP,dev,server.host,localhost,YAML,初始化,hugo
ADD,app.yml,DEFAULT_GROUP,dev,server.port,8080,YAML,初始化,hugo

# 向 list 追加 / 删除指定项
APPEND,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,添加,hugo
DELETE,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,移除,hugo

# 删除 key 或整个配置
DELETE,app.yml,DEFAULT_GROUP,dev,timeout,,YAML,删除 key,hugo
DELETE,app.yml,DEFAULT_GROUP,dev,,,YAML,删除配置,hugo
```

## 执行日志

执行记录存储在两张 MySQL 表中，首次运行时自动创建。

**`nacosbase_changelog`** — 每条脚本一行：

| 列名 | 说明 |
|------|------|
| `script_name` | CSV 文件名（唯一键） |
| `checksum` | 文件内容的 SHA-256，用于篡改检测 |
| `status` | `SUCCESS` / `FAILED` / `ROLLED_BACK` |
| `applied_at` / `applied_by` / `execution_ms` | 执行时间、操作人、耗时 |
| `rollback_data` | 变更前配置的 JSON 快照（供回滚使用） |
| `rolled_back_at` | 回滚时间（可为空） |

**`nacosbase_changelog_item`** — 每个变更集条目一行：

| 列名 | 说明 |
|------|------|
| `changelog_id` | 外键 → `nacosbase_changelog.id` |
| `action` | `ADD` / `MODIFY` / `DELETE` / `APPEND` |
| `data_id` / `config_group` / `namespace` | 目标配置坐标 |
| `content` / `type` | 变更内容及格式 |
| `description` / `operator` / `target_key` | 审计元数据 |

每次 upsert 时，item 记录与父 changelog 行原子替换。

## 模块结构

```
nacosbase-cli → nacosbase-infra → nacosbase-core
```

| 模块 | 说明 |
|------|------|
| `nacosbase-core` | 纯领域层：模型、端口接口、`ChangeEngine`、`ConfigValidator` |
| `nacosbase-infra` | 适配器：Nacos SDK、MySQL/Exposed ORM、CSV 加载器、YAML 配置加载器 |
| `nacosbase-cli` | CLI fat-jar（Clikt）：六条命令，通过 `AppContext` 组装各层 |

## 构建与测试

```bash
./gradlew build
./gradlew test
./gradlew :nacosbase-infra:test      # 需要 Docker（Testcontainers + MySQL）
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.engine.ChangeEngineTest"
```

> `nacosbase-infra` 集成测试依赖 Docker，不可用时自动跳过。
