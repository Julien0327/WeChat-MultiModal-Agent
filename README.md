# 微信多模态智能助手 (WeChat Multi-Modal Agent)

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?style=flat-square)
![DashScope](https://img.shields.io/badge/LLM-Alibaba%20DashScope-blue?style=flat-square)
![Tests](https://img.shields.io/badge/tests-104-success?style=flat-square)

基于 Spring Boot、微信 iLink SDK、微信公众号 SDK 和阿里云 DashScope 构建的微信智能助手。项目支持文本、图片和语音消息，并通过统一工具框架完成天气查询、联网搜索、出行规划、待办、记账、提醒等任务。

A Java 17 / Spring Boot multi-modal AI assistant for WeChat, featuring hybrid intent recognition and a unified tool/skill capability layer.

## English Summary

**WeChat Multi-Modal Agent** is a Java 17 / Spring Boot 3.2 service that turns a personal WeChat account into a multi-modal AI assistant. It connects through the WeChat iLink SDK (QR-code login) and the WeChat Official Account callback, normalizes text, image, voice, and event messages, and replies with text, generated images, or synthesized speech.

The conversation layer uses a hybrid intent pipeline: deterministic local rules handle explicit commands, while Qwen models extract structured intents, resolve omitted details from recent dialogue, and validate every argument before a tool runs. A unified `CapabilityRegistry` exposes 13 built-in tools — weather, place search, web search, trip planning, calculator, translation, todo, expense tracking, reminders, express tracking, and more — through a single interface. A `Skill` abstraction is reserved for composite, multi-step capabilities.

**Engineering highlights**

- **Multi-modal I/O**: image understanding and generation, ASR (`qwen3-asr-flash`), and TTS (`cosyvoice-v3-flash`), with automatic fallback to text.
- **Strict tool calling**: missing parameters trigger a targeted follow-up question instead of a guess; conflicting or unverifiable requests are rejected.
- **Graceful degradation**: when a model or external API is unavailable, the assistant returns an explicit message instead of fabricating a result.
- **Per-user persistence**: todos, expenses, and reminders are stored per WeChat user ID.
- **Extensible by design**: tools implement one interface and are auto-registered by Spring; the project ships with 104 unit and integration tests covering routing, intent classification, tools, and WeChat adapters.

**Tech stack:** Java 17 · Spring Boot 3.2.5 · WxJava 4.6 · WeChat iLink SDK 2.3.3 · Alibaba Cloud DashScope (Qwen) · OkHttp · Jackson · JUnit 5

> Setup and configuration: see [快速开始](#快速开始).

## 项目亮点

- **双通道微信接入**：iLink 扫码登录个人微信，同时保留微信公众号回调，两套通道共用同一套消息处理链路。
- **混合意图识别**：本地规则处理明确指令，千问模型负责复杂表达的意图与参数抽取，并结合多轮上下文补全槽位。
- **统一能力层**：13 个工具与 `Skill` 扩展点通过 `CapabilityRegistry` 统一发现和执行，新增能力只需实现一个接口。
- **严格参数校验**：缺少地点、时间等必填参数时只追问缺失项，不猜测，也不误调用工具。
- **安全降级**：模型、语音或外部接口不可用时返回明确提示，不伪造结果。
- **可验证的工程实践**：104 个单元与集成测试覆盖消息路由、意图识别、工具执行和微信适配器。

## 核心能力

- 微信接入：支持 iLink 扫码登录，并保留微信公众号回调入口。
- 多模态交互：支持文本对话、图片理解、图片生成、语音识别和语音合成。
- 上下文理解：结合最近对话补全用户表达，支持连续追问和多轮参数收集。
- 工具调用：13 个工具统一注册、校验和执行，缺少必要参数时先询问，不猜测地点、时间或业务数据。
- 能力扩展：`AiTool` 与 `Skill` 共用 `CapabilityRegistry` 入口，新增工具或组合技能无需改动消息路由。
- 本地持久化：待办、账单和提醒按微信用户隔离保存。
- 安全降级：模型、语音或外部接口暂时不可用时返回明确提示，避免伪造结果。

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2.5 |
| 微信接入 | wechat-ilink-sdk 2.3.3、WxJava 4.6.0 |
| 大模型 | 阿里云 DashScope 千问系列模型 |
| 网络请求 | OkHttp |
| 数据处理 | Jackson |
| 测试 | JUnit 5、Spring Boot Test（104 个测试） |

## 架构与消息流程

```text
微信消息
  ├─ iLink 通道
  └─ 公众号通道
        ↓
消息路由（文本 / 图片 / 语音 / 事件）
        ↓
意图识别 + 上下文管理 + 参数校验
        ↓
普通对话 / 多模态模型 / 能力注册中心（Tool / Skill）
        ↓
文字、图片或语音回复
```

## 快速开始

### 1. 环境要求

- JDK 17 或更高版本
- Maven 3.8 或更高版本
- 可用的 DashScope API Key
- 使用 iLink 时，需要可扫码登录的微信账号

### 2. 创建本地配置

在项目根目录复制配置模板：

macOS / Linux：

```bash
cp .env.example .env
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

然后编辑 `.env`。项目启动所需的核心配置如下：

```dotenv
DASHSCOPE_API_KEY=你的DashScope_API_Key

ILINK_ENABLED=true
ILINK_ADMIN_TOKEN=自定义的高强度管理Token
ILINK_SESSION_FILE=data/ilink-session.json
```

其他配置按需启用：

| 配置项 | 用途 | 是否必须 |
| --- | --- | --- |
| `WX_APP_ID`、`WX_APP_SECRET`、`WX_TOKEN` | 微信公众号接入 | 仅使用公众号时需要 |
| `WX_AES_KEY` | 公众号兼容或安全模式 | 按公众号模式填写 |
| `AMAP_WEATHER_API_KEY` | 高德地点解析、天气和地点搜索 | 推荐 |
| `TAVILY_API_KEY` | 联网搜索 | 使用搜索工具时需要 |
| `PUBLIC_BASE_URL` | 生成可访问的 MP3 下载链接 | 使用下载链接时需要 |
| `HISTORY_API_URL`、`HISTORY_API_KEY` | 历史上的今天 | 使用该工具时需要 |
| `JOKE_API_URL`、`JOKE_API_KEY` | 随机笑话 | 使用该工具时需要 |
| `EXPRESS_API_URL`、`EXPRESS_CUSTOMER`、`EXPRESS_API_KEY` | 快递查询 | 使用该工具时需要 |
| `TEAM_TOOLS_DATA_DIR` | 待办、账单和提醒的数据目录 | 否，已有默认值 |
| `REMINDER_CHECK_INTERVAL_MS` | 提醒任务扫描间隔 | 否，默认 30 秒 |

缺少可选配置不会阻止应用启动，但对应工具会返回清晰的缺失配置提示。修改 `.env` 后需要重启应用。

### 3. 测试并启动

```bash
mvn test
mvn spring-boot:run
```

默认服务地址为 `http://127.0.0.1:8080`。

## 微信接入

### iLink 扫码登录

> 以下命令以 PowerShell 为例；macOS / Linux 可将 `Invoke-WebRequest` / `Invoke-RestMethod` 替换为 `curl`。

应用启动后，通过管理接口生成二维码：

```powershell
$headers = @{ "X-ILink-Admin-Token" = "你在 .env 中配置的 ILINK_ADMIN_TOKEN" }

Invoke-WebRequest -Method Post `
  -Uri "http://127.0.0.1:8080/ilink/admin/login.png" `
  -Headers $headers `
  -OutFile "ilink-login.png"
```

打开 `ilink-login.png`，使用微信扫码并确认。登录成功后，可查询状态：

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:8080/ilink/admin/status" `
  -Headers $headers
```

如果本地会话已失效，强制生成新的二维码：

```powershell
Invoke-WebRequest -Method Post `
  -Uri "http://127.0.0.1:8080/ilink/admin/login.png?force=true" `
  -Headers $headers `
  -OutFile "ilink-login.png"
```

iLink 登录信息默认保存在 `data/ilink-session.json`。该文件包含登录凭据，不得提交到 Git 或发送给其他人。

### 微信公众号

公众号消息回调入口为：

```text
http(s)://你的域名/wx/msg
```

公众号后台的 Token、AppID、AppSecret、消息加密模式和回调地址必须与本地配置保持一致。不使用公众号时，可只启用 iLink 通道。

## 工具与能力

项目通过 `AiTool` 接口定义工具，由 `ToolRegistry` 统一注册和执行；`CapabilityRegistry` 向上提供统一的工具/技能发现入口，`Skill` 接口用于封装需要多步工具编排的组合能力（当前为扩展点）。

| 工具 | 功能 | 数据来源或依赖 |
| --- | --- | --- |
| `weather` | 实时天气与未来 7 天预报 | 高德、Open-Meteo |
| `place_search` | 景点、餐厅、医院等地点搜索 | 高德 Web 服务 |
| `web_search` | 联网检索并返回来源 | Tavily |
| `world_clock` | 查询指定地点当前时间 | Java 时区数据 |
| `trip_plan` | 组合地点、天气和搜索结果规划行程 | 多工具协作 |
| `calculator` | 加、减、乘、除计算 | 本地计算 |
| `translate` | 多语言翻译 | DashScope |
| `history_today` | 查询历史上的今天 | 外部历史数据接口 |
| `joke` | 获取随机笑话 | 外部笑话接口 |
| `todo` | 新增、查看和完成待办 | 本地 JSON |
| `expense` | 记账、查询明细和生成月报 | 本地 JSON |
| `reminder` | 设置、查看和取消提醒 | 本地 JSON、定时任务 |
| `express_tracking` | 查询快递物流 | 快递100 |

示例：

```text
成都未来三天天气怎么样
江油附近有哪些景点
从江油出发，明天去成都玩两天
把“明天见”翻译成英文
帮我记个待办：周五交报告
午饭花了 25 元，记到餐饮
10 分钟后提醒我喝水
查询顺丰快递 SF1234567890
```

## 意图识别与上下文

文本消息使用“本地规则 + 千问结构化分类”的混合方案：

1. 明确的帮助、清除上下文、天气、地点、图片生成等请求优先由本地规则识别。
2. 复杂表达交给模型提取意图和参数，并结合最近对话理解“那里”“明天”“玩两天”等省略信息。
3. 一个句子可同时提取出发地、目的地、时间、时长和偏好，不按单一关键词截断。
4. 天气地点、出行出发地等必填参数缺失时，系统进入待补全状态并只追问缺失项。
5. 后续回答会与原请求合并，不把“今天晚上”“海边”“近一点”等时间或偏好误填为地点。
6. 置信度不足、参数冲突或外部数据不可验证时，不调用工具并要求用户确认。
7. 意图模型不可用或结构化结果解析失败时，安全降级为普通聊天。

## 多模态与回复方式

### 图片

- 支持识别用户发送的图片。
- 支持根据文本提示生成图片并发送给用户。

### 语音

收到语音后，程序会下载音频并使用 `qwen3-asr-flash` 转写，再进入与文本相同的意图识别流程。用户明确要求语音回复时，使用 `cosyvoice-v3-flash` 合成语音；失败时自动降级为文字。

默认回复方式为文字。回复方式与业务意图分离，用户可以通过“用语音回答”或“用文字回答”临时切换。

## 数据与安全

- `.env`、`data/ilink-session.json` 和用户本地数据不得提交到仓库。
- `.env.example` 只能保留配置名称和占位值，不得填写真实密钥。
- 日志、截图、Issue 和群聊中不得粘贴 API Key、微信凭据或用户消息数据。
- 待办、账单和提醒按微信用户隔离，默认保存在 `data/team-tools`。
- 主动提醒依赖应用持续运行并保持微信通道在线。
- 密钥疑似泄露时，应立即在对应平台撤销并重新生成。

## 项目结构

```text
src/main/java/com/group4/wxai
├─ config/                 配置映射、HTTP 客户端和微信配置
├─ controller/             公众号回调、iLink 管理和音频下载接口
├─ handler/                文本、图片、语音和事件消息处理
├─ model/                  意图、上下文、回复模式和出行参数模型
└─ service/
   ├─ capability/          工具与技能的统一发现和执行入口
   ├─ ilink/               iLink 会话、收发消息和媒体处理
   ├─ intent/              混合意图识别
   ├─ llm/                 DashScope 调用与回复整理
   ├─ skill/               组合技能扩展点
   ├─ tool/                工具实现、注册、校验和本地数据存储
   ├─ weather/             天气查询
   └─ wx/                  公众号消息和媒体服务

src/test/java/             单元测试与集成测试
.env.example               本地配置模板
```

## 测试

项目包含 104 个单元测试与集成测试，覆盖：

- 消息路由与微信适配：`MessageRouter`、iLink 收发、媒体处理、公众号消息。
- 意图识别：混合分类、缺参追问、多轮补全、上下文清理。
- 工具执行：天气、地点、联网搜索、出行规划、世界时钟、团队工具等。
- 能力层：`ToolRegistry`、`SkillRegistry`、`CapabilityRegistry` 的注册、冲突检测与执行。

运行全部测试：

```bash
mvn test
```

## 开发约定

1. 从最新主分支创建功能分支，分支名使用 `feature/<功能名>` 或 `fix/<问题名>`。
2. API Key 和本地登录态只写入 `.env` 或 `data/`，禁止进入提交记录。
3. 新增工具时实现 `AiTool`，同时补充参数校验、配置缺失提示和对应测试。
4. 修改意图识别时同时验证普通聊天、缺参追问、多轮补全和误调用工具四类场景。
5. 提交合并请求前运行 `mvn test`，并在说明中列出新增配置项和验证结果。
6. 主分支通过合并请求更新，避免直接推送未经评审的改动。

## 常见问题

### 应用能启动，但某个工具不可用

检查该工具对应的环境变量是否已经写入 `.env`，然后重启应用。可选工具缺少配置时不会影响其他功能。

### iLink 扫码后仍未登录

先查询 `/ilink/admin/status`。如果旧会话失效，使用 `?force=true` 重新生成二维码。

### 天气或地点结果不准确

确认已配置高德 Web 服务类型的 Key，并尽量提供完整的城市或地区名称。程序不会在缺少地点时自行猜测。

### 语音回复没有 MP3 下载链接

确认 `PUBLIC_BASE_URL` 是外部可访问的服务根地址。未配置时不影响微信内的普通文字回复。

### 提醒没有按时发送

确认应用仍在运行、微信通道在线，并检查 `REMINDER_CHECK_INTERVAL_MS` 和系统时间。
