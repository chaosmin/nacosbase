[English](README.md) | 简体中文

# nacosbase

**nacosbase** 是一款参考 [Liquibase](https://www.liquibase.org/) 设计理念的 Nacos 配置版本管理工具。它将数据库迁移式的工作流引入 Nacos 配置管理，为团队提供可重复、可审计、可脚本化的配置变更能力，适用于多环境场景。

## 核心概念

| Liquibase | nacosbase |
|-----------|-----------|
| ChangeSet | CSV 变更脚本 |
| ChangeLog | 有序变更脚本集合 |
| DATABASECHANGELOG | `nacosbase_changelog` MySQL 表 |
| 校验和验证 | 已应用脚本的篡改检测 |

## 快速开始

### 1. 环境要求

- JDK 21+
- MySQL 8.x（用于记录已应用的变更脚本）
- Nacos Server 2.x

### 2. 构建 CLI fat-jar

```bash
./gradlew :nacosbase-cli:shadowJar          # macOS/Linux
gradlew.bat :nacosbase-cli:shadowJar        # Windows
# 输出：nacosbase-cli/build/libs/nacosbase-0.1.0.jar
```

### 3. 创建 `nacosbase.yml`

```yaml
nacos:
  serverAddr: 127.0.0.1:8848
  username: nacos
  password: ${NACOS_PASSWORD}           # 从环境变量读取

datasource:
  url: jdbc:mysql://localhost:3306/nacosbase
  username: root
  password: ${DB_PASSWORD}              # 从环境变量读取

changelog:
  scriptsDir: ./changelogs              # CSV 变更脚本目录
  appliedBy: ${USER:-nacosbase}         # 记录在执行日志中的操作人
```

密码**必须**通过环境变量提供（`${VAR}` 或 `${VAR:-默认值}`），禁止在配置文件中硬编码凭据。

### 4. 导出当前基线

```bash
java -jar nacosbase-0.1.0.jar baseline \
  --namespace dev \
  --output changelogs/000-baseline.csv
```

连接 Nacos，将 `namespace=dev` 下的所有配置导出为 CSV 快照。

### 5. 编写变更脚本

在 `scriptsDir` 目录（如 `changelogs/`）下创建带数字前缀的 CSV 文件，脚本按数字前缀升序依次执行。

**`changelogs/001-add-redis.csv`**

```csv
action,dataId,group,namespace,content,type,description
ADD,redis.yml,DEFAULT_GROUP,dev,"host: localhost
port: 6379
",YAML,添加 Redis 配置
```

支持的操作：`ADD`（新增）、`MODIFY`（修改）、`DELETE`（删除）。

### 6. 预览变更（Dry Run）

```bash
java -jar nacosbase-0.1.0.jar diff --config nacosbase.yml
```

若 Nacos 当前状态与脚本期望状态一致，退出码为 `0`；存在差异时退出码为 `1`。示例输出：

```
[ADD]     redis.yml @ DEFAULT_GROUP/dev
[MODIFY]  app.yml @ DEFAULT_GROUP/prod
```

### 7. 应用变更

```bash
java -jar nacosbase-0.1.0.jar update --config nacosbase.yml
```

已应用的脚本会被跳过。若已应用脚本的校验和与当前文件不匹配，执行立即中止。

### 8. 查看执行状态

```bash
java -jar nacosbase-0.1.0.jar status --config nacosbase.yml
```

列出所有变更脚本及其执行状态（`SUCCESS`、`FAILED`、`ROLLED_BACK`）和应用时间。

### 9. 回滚变更

```bash
# 回滚最近一条已应用的脚本（默认）
java -jar nacosbase-0.1.0.jar rollback --config nacosbase.yml

# 回滚最近 N 条脚本
java -jar nacosbase-0.1.0.jar rollback --count 3 --config nacosbase.yml
```

### 10. 离线校验脚本

```bash
java -jar nacosbase-0.1.0.jar validate --scripts ./changelogs
```

检查 CSV 格式与数字前缀重复问题，无需连接 Nacos 或 MySQL。通过时退出码为 `0`。

## CLI 命令速查

```
用法：nacosbase [命令] [选项]

命令：
  baseline   将当前 Nacos 配置导出为基线 CSV
  update     将待应用的变更脚本应用到 Nacos
  status     查看执行日志（已应用脚本及其状态）
  diff       预览 update 将要执行的变更
  validate   离线校验 CSV 变更脚本
  rollback   回滚一条或多条已应用的变更脚本

各命令公共选项：
  --config   nacosbase.yml 路径（默认：nacosbase.yml）
```

## CSV 脚本格式

| 列名 | 是否必填 | 说明 |
|------|----------|------|
| `action` | 是 | `ADD`、`MODIFY` 或 `DELETE` |
| `dataId` | 是 | Nacos DataID |
| `group` | 是 | Nacos group（如 `DEFAULT_GROUP`） |
| `namespace` | 是 | Nacos 命名空间 ID |
| `content` | ADD/MODIFY 必填 | 配置内容（多行内容需用引号包裹） |
| `type` | ADD/MODIFY 必填 | `YAML`、`PROPERTIES`、`JSON`、`TEXT`、`TOML` |
| `description` | 否 | 人类可读的变更描述 |

脚本文件名须符合 `{N}-{描述}.csv` 格式，其中 `N` 为正整数。`validate` 命令会拒绝重复前缀。

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
# 构建所有模块
./gradlew build

# 运行所有测试
./gradlew test

# 运行指定模块的测试
./gradlew :nacosbase-core:test
./gradlew :nacosbase-infra:test      # 需要 Docker（Testcontainers + MySQL）
./gradlew :nacosbase-cli:test

# 运行单个测试类
./gradlew :nacosbase-core:test --tests "com.nacosbase.core.engine.ChangeEngineTest"

# 构建 CLI fat-jar
./gradlew :nacosbase-cli:shadowJar
```

> **注意：** `nacosbase-infra` 集成测试依赖 Docker。Docker 不可用时，测试会自动跳过而非失败。

## 设计参考

nacosbase 的核心概念大量借鉴自 [Liquibase](https://www.liquibase.org/)。如果你曾经使用 Liquibase 管理数据库迁移，其心智模型可以直接迁移过来：编写带编号的变更脚本，执行 `update` 应用变更，使用 `rollback` 回退。
