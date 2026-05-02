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
- 并行工具引擎：统一 4 路并发 + 批次超时取消，支持 ReAct/Plan/Multi-Agent
- 联网搜索：策略模式 SearchProvider（智谱/SerpAPI/SearXNG）+ 自动热切换
- 网页抓取：Jsoup Readability 两阶段正文提取 + 网络安全策略
- MCP 集成：JSON-RPC 2.0 客户端 + stdio/http-sse 传输 + 多 Server 并行启动
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
- `/mcp`：查看 MCP 服务状态（`/mcp status`）
- `/clear`：清空会话历史
- `/exit`：退出

## 联网配置

### 1. 先理解这几个工具

- `web_search`：联网搜索网页，返回标题、链接、摘要
- `web_fetch`：抓取指定网页并提取正文内容
- Agent 会根据问题自动决定是否联网调用这两个工具

### 2. 配置读取顺序（很重要）

程序读取配置优先级：

`环境变量 > Java 系统属性(-Dxxx=yyy) > 工作区 .env`

也就是说，同一个配置项如果你都写了，最终以环境变量为准。

### 3. 最简单配置（小白推荐）

在项目根目录创建 `.env` 文件（如果已有就直接编辑），写入：

```env
SEARCH_PROVIDER=searxng
SEARXNG_BASE_URL=https://searx.be
```

这是免 Key 的方案，最容易跑通。

### 4. 三种搜索引擎怎么选

#### A. SearXNG（推荐新手先用）

- 适合：先跑通功能，不想申请 Key
- 必填：`SEARCH_PROVIDER=searxng`
- 可选：`SEARXNG_BASE_URL`（不填默认 `https://searx.be`）

示例：

```env
SEARCH_PROVIDER=searxng
SEARXNG_BASE_URL=https://searx.be
```

#### B. SerpAPI

- 适合：结果稳定、商业搜索 API
- 必填：`SEARCH_PROVIDER=serpapi` + `SERPAPI_API_KEY`

示例：

```env
SEARCH_PROVIDER=serpapi
SERPAPI_API_KEY=你的_serpapi_key
```

#### C. 智谱（Zhipu）

- 适合：你已有智谱账号和 Key
- 必填：`SEARCH_PROVIDER=zhipu` + `ZHIPU_API_KEY`
- 可选：`ZHIPU_SEARCH_URL`（默认已内置）

示例：

```env
SEARCH_PROVIDER=zhipu
ZHIPU_API_KEY=你的_zhipu_key
ZHIPU_SEARCH_URL=https://open.bigmodel.cn/api/paas/v4/chat/completions
```

### 5. Windows PowerShell 设置环境变量（可选）

如果你不想写 `.env`，也可直接设环境变量：

- 当前终端临时生效：

```powershell
$env:SEARCH_PROVIDER="serpapi"
$env:SERPAPI_API_KEY="你的key"
```

- 永久生效（新开终端/重启 IDE 后生效）：

```powershell
setx SEARCH_PROVIDER "serpapi"
setx SERPAPI_API_KEY "你的key"
```

### 6. 常见问题排查

- 报 `web_search_failed`：
  - 检查 `SEARCH_PROVIDER` 拼写是否正确（只能是 `zhipu|serpapi|searxng`）
  - 检查对应 Key 是否存在、是否过期
- 抓取失败 `web_fetch_failed`：
  - URL 是否可公网访问
  - 是否被安全策略拦截（内网地址、localhost、非法 scheme 会被拒绝）

## MCP 配置

### 1. MCP 是什么

- MCP Server 可以给 CLI 提供第三方工具能力
- 启动后会自动发现工具并注册到 Agent
- 工具名会自动加命名空间：`mcp__服务名__工具名`

例如：`mcp__git__status`

### 2. 配置入口

通过环境变量 `MCP_SERVERS` 配置一个或多个 Server。

### 3. 配置格式（重点）

整体结构：

`server1;server2;server3`

每个 server 两种写法：

- `stdio` 模式：`name|stdio|command|arg1,arg2,arg3`
- `http` 模式：`name|http|https://your-mcp-host`

### 4. 配置示例

#### 单个 stdio Server

```env
MCP_SERVERS=git|stdio|node|mcp-git-server.js,--stdio
```

说明：
- `git` 是服务名
- `node` 是启动命令
- `mcp-git-server.js,--stdio` 是参数（逗号分隔）

#### 单个 HTTP Server

```env
MCP_SERVERS=docs|http|https://mcp.example.com
```

#### 多 Server 同时配置

```env
MCP_SERVERS=git|stdio|node|mcp-git-server.js,--stdio;docs|http|https://mcp.example.com
```

### 5. 启动后怎么确认成功

- 启动 CLI 后看控制台是否有 `[MCP] 已加载` 提示
- 在 CLI 输入：

```text
/mcp status
```

如果有服务名列表，说明已加载成功。

### 6. 安全与审批说明

- 所有 `mcp__` 工具默认进入 HITL 审批（人工确认）
- 审批开关：
  - `/hitl on`
  - `/hitl off`
  - `/hitl status`
- 审计日志会记录在工作区：`.dongran_audit.jsonl`

### 7. 常见问题排查

- 看不到 MCP 工具：
  - 检查 `MCP_SERVERS` 格式是否有多余空格或少了 `|`
  - 确认 stdio 命令在本机可直接运行
  - HTTP 地址是否可访问（含 `/sse`、`/rpc`）
- 启动就失败：
  - 先单独在终端运行该 MCP Server，确认它本身正常

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
