# group-4 微信 iLink 智能助手

基于 Spring Boot、微信 iLink SDK 和阿里云 DashScope 的微信智能助手，支持扫码登录、文本对话、图片理解、文生图、语音识别和多工具调用。原微信公众号回调入口暂时保留用于兼容，iLink 是新的主要消息通道。

## 本地配置

项目不会把 iLink 登录凭证或 DashScope API Key 写进源码。先复制配置模板：

```powershell
Copy-Item .env.example .env
notepad .env
```

至少填写 `DASHSCOPE_API_KEY`、`ILINK_ADMIN_TOKEN`，以及高德、Tavily 等所需配置后启动：

```powershell
mvn spring-boot:run
```

## 微信 iLink 扫码登录

应用启动后，使用管理 Token 生成登录二维码。PowerShell 示例：

```powershell
$headers = @{ "X-ILink-Admin-Token" = "你在 .env 中设置的 ILINK_ADMIN_TOKEN" }
Invoke-WebRequest -Method Post `
  -Uri "http://127.0.0.1:8080/ilink/admin/login.png" `
  -Headers $headers `
  -OutFile "ilink-login.png"
```

打开 `ilink-login.png` 并用微信扫码确认。登录成功后，凭证保存在 `data/ilink-session.json`，该目录已被 Git 忽略；不要上传或分享这个文件。查询状态：

如果本地保存的登录态已经失效，在登录地址后加 `?force=true`，程序会清理旧会话并重新生成扫码二维码：

```powershell
Invoke-WebRequest -Method Post `
  -Uri "http://127.0.0.1:8080/ilink/admin/login.png?force=true" `
  -Headers $headers `
  -OutFile "ilink-login.png"
```

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/ilink/admin/status" -Headers $headers
```

iLink 使用长轮询接收消息，不需要在微信后台配置公网回调 URL。目标用户必须先向 Bot 发消息，SDK 获取最新 `contextToken` 后才能回复。

## 兼容的公众号入口

旧的微信公众号回调仍暂时保留：

```text
http(s)://你的公网域名/wx/msg
```

如果不再使用公众号，可将 `WX_*` 保持为占位值。`.env` 已被 Git 忽略，请勿提交或发送给其他人。

## 意图识别

文本消息采用“本地规则 + 千问结构化分类”的混合方式：

1. 帮助、清除上下文、明确的画图、天气和地点搜索命令优先在本地判断，不消耗模型调用。
2. 模糊表达交给 `qwen-flash`，并结合最近 6 条对话返回结构化 JSON。
3. 程序支持 `CHAT`、`WEATHER`、`PLACE_SEARCH`、`WEB_SEARCH`、`WORLD_CLOCK`、`TRIP_PLAN`、`TEAM_TOOL`、`GENERATE_IMAGE`、`CLEAR_CONTEXT`、`HELP`、`UNKNOWN` 等意图。
4. 动作置信度低于 `0.75`、工具参数缺失、图片提示词缺失或消息不明确时，不执行操作，而是追问用户。
5. 意图模型不可用或 JSON 无法解析时，安全降级为普通聊天。

意图识别与其他 DashScope 模型共用 `DASHSCOPE_API_KEY`，不需要额外密钥。

## 对话风格

普通聊天采用匿名性格规则：回复简洁直接，遇到代码、AI 和具体问题时认真展开，轻松话题可以自然幽默，但不使用热情客服腔。模型不知道这些规则源自任何现实人物，也不会声称在模仿某个人。

最终回复还会经过聊天文本整理：去除“助手：”“AI：”等角色标签和常见未填充占位符，同时保留确有需要的代码块、步骤和工具数据。缺少必要信息时直接追问，不使用 `[姓名]`、`<用户>`、`{user_name}` 或 `XXX` 代替真实内容。

## AI 工具

应用通过统一的 `AiTool` 接口和 `ToolRegistry` 注册、查找并执行受控工具。参数先由意图层校验，缺少必填项时先追问；结果来自程序计算、持久化数据、已配置的数据接口或指定模型能力：

- `weather`：包装现有 `WeatherService`，查询实时天气和未来 7 天预报。
- `place_search`：调用高德地点关键字搜索，在指定城市查询景点、餐厅、医院、学校等 POI。
- `web_search`：使用 Tavily 联网搜索，并返回来源链接。
- `world_clock`：根据 IANA 时区数据计算指定地点的当前时间。
- `trip_plan`：在出发地、目的地、时间和时长齐全后组合天气、地点与搜索结果。
- `calculator`：执行明确的加、减、乘、除计算，除数为 0 时拒绝执行。
- `translate`：复用现有千问文本模型翻译用户明确给出的原文，不需要新 Key。
- `history_today`：查询指定日期“历史上的今天”，依赖独立历史数据 API。
- `joke`：从笑话数据 API 获取真实数据，不让模型临时编造接口结果。
- `todo`：按微信用户新增、查看和完成待办，数据持久化到本地 JSON。
- `expense`：按微信用户记账、查询月度明细和生成分类月报。
- `reminder`：设置、查看和取消提醒；服务器持续运行时会主动推送微信消息。
- `express_tracking`：通过快递100查询物流；缺少公司或单号时先追问，不自行猜测。

### 团队工具配置

待办、账单和提醒开箱即用，默认数据目录为 `data/team-tools`；该目录已经被 Git 忽略。翻译复用
`DASHSCOPE_API_KEY`。历史、笑话和快递工具需要从对应组员处取得配置后写入 `.env`：

```dotenv
# jiangxiaofeng 分支使用的历史数据接口
HISTORY_API_URL=
HISTORY_API_KEY=

# jiangxiaofeng 分支使用的笑话数据接口
JOKE_API_URL=
JOKE_API_KEY=

# anliting 分支使用的快递100企业实时查询凭据
EXPRESS_API_URL=https://poll.kuaidi100.com/poll/query.do
EXPRESS_CUSTOMER=
EXPRESS_API_KEY=
```

配置缺失不会阻止项目启动；只有调用相应工具时才会提示缺少哪一项。补齐 `.env` 并重启服务后即可启用，
不需要再次修改 Java 代码。提醒工具无需额外 API，但需要服务器和微信 iLink/公众号会话保持在线。

示例表达：

- `计算 12.5 × 4`
- `把“明天见”翻译成英文`
- `帮我记个待办：明天下午交报告`
- `午饭花了 25 元，记到餐饮`
- `查看本月支出月报`
- `10 分钟后提醒我喝水`
- `查询顺丰快递 SF1234567890`

### 天气查询

天气不会让大模型凭记忆回答，而是由 `WeatherService` 实时获取：

1. 将“北京”“成都市”等地点解析成明确的经纬度，并在回复中回显解析后的省份和国家。
2. 查询当前温度、体感温度、湿度、风速、降水，以及未来 7 天的最高/最低温和降水概率。
3. 支持“北京天气”“成都明天天气”“上海未来三天天气”等表达；其他自然语言由意图模型提取地点和日期。
4. 天气结果默认文字回复；明确要求“用语音回答”时仍可合成为语音。

配置 `AMAP_WEATHER_API_KEY` 后，程序优先使用高德 Web 服务的地址编码和区县天气接口；Key 未配置或高德临时不可用时，会降级到 Open-Meteo，项目仍可正常启动。注册时请选择“Web 服务 API”类型的 Key，然后把它填入 `.env`：

```dotenv
AMAP_WEATHER_API_KEY=你的高德Web服务Key
```

地点搜索复用同一个高德 Web 服务 Key，无需额外申请密钥。例如：“绵阳有哪些景点”“江油哪里有医院”“成都有什么火锅店”。

回复形式与业务意图相互独立，由 `AUTO`、`TEXT`、`VOICE` 三种回复方式控制：

- 文字和语音消息都默认回复文字。
- 用户明确说“用语音回答”或“用文字回答”时，覆盖消息类型的默认方式。
- `CHAT`、`HELP`、`CLEAR_CONTEXT` 和澄清问题都可以按回复方式输出；生成图片仍发送图片。
- 语音合成或微信语音发送失败时，自动降级为文字，避免用户收不到答案。

## 语音处理

收到微信语音后：

1. 根据 `media_id` 下载原始语音。
2. 使用 `qwen3-asr-flash` 在程序内转成文字。
3. 将转写文字交给与文本消息相同的意图识别和功能路由。
4. 只有程序内识别失败时，才使用微信提供的 `Recognition` 文字兜底。
5. 默认发送文字回答；用户明确要求语音回答时，使用 `cosyvoice-v3-flash` 合成为 MP3。iLink 通道把它作为音频文件发送，并可同时发送短时下载链接。

程序内语音识别和语音合成与其他 DashScope 模型共用 `DASHSCOPE_API_KEY`，不需要第二个 API Key。

