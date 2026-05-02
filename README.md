# Dongran CLI

一个面向工程场景的交互式命令行 AI 助手（Java 21 + Maven），目标是提供类似 Claude Code CLI 的基础能力：多轮对话、工具调用、会话记忆、配置管理与可扩展插件机制。

## 当前能力

- 交互式会话：持续多轮对话
- Plan-and-Execute：先由 Planner 生成 DAG 任务计划，再按依赖执行
- Multi-Agent Team：支持 Planner / Worker / Reviewer 主从协作（`/team on` 开启）
- 并发调度：同层可执行任务并发运行
- 失败恢复：任务级重试与失败隔离（下游自动跳过）
- 记忆系统：短期记忆窗口 + 长期记忆持久化检索
- 上下文压缩：长会话自动摘要旧上下文
- Token 预算：按预算动态裁剪上下文，预留推理空间
- RAG 检索：JavaParser AST 多粒度分块 + SQLite 向量库 + 混合检索
- 代码图谱：支持 extends/implements/imports/calls/contains 关系与调用链查询
- HITL 审批：危险工具人工确认、支持会话级“全部放行”缓存
- 工具调用：`read_file` / `write_file` / `list_dir` / `run_command`
- 安全边界：
  - 文件工具限制在工作区目录
  - 命令工具采用白名单 + 超时控制
- 会话记忆：自动保存到 `.dongrancli.session.json`
- 配置管理：支持 `.env`、工作区配置、用户配置、环境变量
- 可扩展性：基于 `ServiceLoader` 的 `ToolProvider` 插件接口

## 快速开始

1. 配置 API Key（任选一种）：
   - 环境变量：`DEEPSEEK_API_KEY=xxx`（推荐）
   - 兼容旧变量：`GLM_API_KEY=xxx`
   - 工作区 `.env`：`DEEPSEEK_API_KEY=xxx`（也兼容 `GLM_API_KEY`）
   - 工作区 `.dongrancli.properties`：`api.key=xxx`
   - 用户配置 `~/.dongrancli/config.properties`：`api.key=xxx`
2. 编译与运行：

```bash
mvn clean package
java -jar target/DongranCli-1.0-SNAPSHOT.jar
```

## CLI 命令

- `/help`：显示帮助
- `/version`：显示版本
- `/config`：显示当前配置（敏感信息脱敏）
- `/history`：显示会话消息数量
- `/plan`：显示最近一次 DAG 计划执行状态
- `/team`：多 Agent 模式控制（`/team on|off|status|log`）
- `/hitl`：人工审批控制（`/hitl on|off|status|reset`）
- `/clear`：清空会话历史
- `/exit`：退出

## 配置优先级

`环境变量 > 配置文件 > 默认值`

- `DEEPSEEK_API_KEY`（兼容 `GLM_API_KEY`）/ `api.key`
- `DONGRAN_MODEL` / `model`（默认 `deepseek-chat`）
- `DONGRAN_API_URL` / `api.url`（默认 `https://api.deepseek.com/chat/completions`）

## Provider 切换

- `MODEL_PROVIDER=openai`：走 OpenAI 兼容接口（默认）
- `MODEL_PROVIDER=ollama`：走本地 Ollama
- Ollama 可选：`OLLAMA_BASE_URL`（默认 `http://localhost:11434`）
- Embedding 模型可选：`EMBEDDING_MODEL`（默认 `nomic-embed-text`）

## RAG 检索说明

- Java 文件：按 文件/类/方法 三层 AST 分块
- 非 Java 文件：按固定大小分段
- `search_code` 工具会自动触发：
  - 语义检索（向量余弦）
  - jieba 分词加权
  - 代码类型加分（类/方法/文件）
  - 同文件结果限流
- 若查询中包含“调用链 + 类名”，会基于关系图谱返回调用链

## 插件扩展

实现 `com.dr.tool.ToolProvider` 并通过 `META-INF/services/com.dr.tool.ToolProvider` 注册后，启动时会自动加载插件工具。

## 说明

当前版本已完成生产化基线改造，但自动补全、富文本提示和更细粒度权限模型建议在下个迭代接入（推荐 `JLine + Picocli`）。
