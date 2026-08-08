# 主动助理每日简报协议（v1）

首版只支持由设备定时触发的固定每日简报，不开放任意提示词、网址或工具名。设备负责本地时间、重复规则、持久化和触发；服务端只负责实时获取天气/新闻并播报。

```json
{"jsonrpc":"2.0","method":"notifications/assistant/triggered","params":{"version":1,"id":17,"event_id":"17-20260810T080000","workflow":"daily_briefing","sections":["weather","news"],"location":"广州","triggered_at":"2026-08-10T08:00:00","speak":true}}
```

- `sections` 仅允许 `weather`、`news`，不可重复，最多两项；包含天气时 `location` 必填且不超过 40 个 Unicode 字符。
- `event_id` 必须等于 `{id}-{triggered_at去掉横线和冒号}`，用于同一连接内去重。
- 服务端并行请求模块，每个模块最多等待 8 秒；单模块失败仍播报其余模块，全部失败播报固定不可用提示。
- 新闻最多 3 条，总播报文本最多 420 字符，使用固定模板，不经过 LLM 自主改写。
- 新用户语音轮次、连接关闭或设备停止任务时，沿用主动 TTS 生命周期取消播报。
- 每日简报若确实打断了正在播放的网易云歌曲，服务端在本次主动 TTS 完整结束后自动恢复原队列和原歌曲；当前流式播放不保存精确进度，因此从该歌曲开头重新播放。用户在简报期间插话、发起新播放请求、停止音乐或连接关闭时不得恢复。普通闹铃和提醒继续保持不恢复音乐。
- v1 不接入日历、不支持自定义工作流。重复简报在设备重启后若已错过则跳到下一次；单次任务沿用 5 分钟恢复窗口。

## 积极主动模式（v2）

积极主动模式只在已有可靠上下文时给出短建议，不允许模型凭空制造提醒。主动建议按设备标识共享策略状态并为同一主题设置冷却时间；`active` 默认每日 5 次，`aggressive` 不受每日总额度限制，服务进程重启后进程内状态重新计算。当前没有用户作息数据，因此不擅自设置默认安静时段。

- 普通提醒播报时可追加“处理完告诉我一声”，随后沿用既有自动收听状态，让用户直接确认；闹铃不追加完成确认。
- 网易云队列自然播放完毕后，最多每 2 小时询问一次是否继续播放相似歌曲，并把该问题写入对话上下文，使“好、继续”等回答能够正确关联。
- 网易云音乐连续两次失败时，最多每小时给出一次检查登录状态的建议；单次失败仍只返回原始明确错误。
- 用户只表达“晚点、有空、回头做某事”但没有明确要求提醒或没有时间时，必须先询问是否需要提醒及具体时间，禁止创建不完整任务。
- 设备发现同类型、同内容、同一时刻但不同日期的多个单次闹铃或提醒时，在创建确认后建议改成重复任务。该判断只使用设备上真实存在的任务，不根据模型猜测习惯。

### v2 管理数据契约

本节仅定义 manager-api 的持久化与管理接口，不改变上面的设备通知协议或播报语义。内部接口继续使用现有 server-secret 鉴权；用户接口继续使用登录令牌，并且所有按 `device_id` 的操作都校验设备属于当前用户。无权访问与不存在统一返回“设备不存在”，避免枚举其他用户的设备。

内部接口：

- `GET /config/proactive/preferences/{macAddress}`、`PUT /config/proactive/preferences/{macAddress}`：读取或更新设备偏好。
- `POST /config/proactive/events`：按设备内唯一 `(device_id, event_id)` 幂等写入事件；相同 `event_id` 的重试必须与已存审计内容完全一致，否则明确报错。不同设备可以使用相同 `event_id`。`dedupe_key` 仅供策略层关联和去重，不是审计唯一键，因此同一故障及其恢复事件可以分别留痕。
- `PUT /config/proactive/events/{eventId}/status`：请求体必须带 `mac_address`；claimed 事件还必须带领取时的 `claim_token`，状态与令牌共同参与 CAS。
- `POST /config/proactive/events/{eventId}/claim`：请求体带 `mac_address` 和调用方生成的唯一 `claim_token`。数据库以 180 秒租约将未过期 `pending` 原子改为 `claimed`；响应丢失后使用同 token 重试仍返回成功，租约过期后允许新 token 重领，旧 token 不得写终态。明确返回 `false` 表示其他连接持有有效租约；manager-api 请求异常时，critical 或恢复类健康通知按安全契约继续播报。
- `POST /config/proactive/habits/observe`：按 `(device_id, habit_type, habit_key)` 原子累加证据；证据达到 3 次后进入候选。
- `GET /config/proactive/habits/candidates?mac_address=...`：列出尚未接受或忽略的建议候选。

manager-api 请求体中的 `created_at`、`expires_at` 和 `seen_at` 使用 Unix 毫秒整数；设备通知协议中的同名统一事件字段仍使用 UTC 基准的 Unix 秒整数。设备若在系统墙钟中叠加 OTA `timezone_offset`，发送前必须扣除该偏移；服务端在审计边界显式完成秒到毫秒转换。manager-api 响应中的 `Date` 为 `yyyy-MM-dd HH:mm:ss`，再次写入前也必须规范化成毫秒，不能把任一格式化时间字符串直接写回。

用户接口：

- `GET /device/proactive/preferences`、`GET|PUT /device/proactive/preferences/{deviceId}`：列出本人设备或管理单个设备偏好。
- `PUT /device/proactive/preferences/{deviceId}/today-silent`：静默至服务端所在时区的次日零点。
- `GET /device/proactive/events`：分页参数为 `page`（1 至 1000）和 `limit`（1 至 100），可选 `device_id`、`topic`、`delivery_status`、`event_type` 过滤；未给 `device_id` 时只查询本人全部绑定设备。
- `GET /device/proactive/habits`、`DELETE /device/proactive/habits/{habitId}`：列出本人设备的习惯或删除指定候选；列表可选 `device_id`。
- `GET|PUT /device/proactive/monitors/{deviceId}`：原子读取或同时更新本人设备的天气、新闻监测配置。天气默认 30 分钟、新闻默认 10 分钟，两类默认启用；配置字段严格校验，未知字段拒绝，普通监测首次运行以空 `state` 建立基线。
- `GET /device/proactive/pending`：设备使用 `Device-Id`、`Client-Id` 和 Bearer HMAC 令牌鉴权。每次探测更新两类监测的 `last_probe_at`，只返回一个未过期、可领取的 `weather_alert/news_alert` 安全信封，不返回 payload 或 reason；无事件时返回 `pending=false,retry_after_seconds=300`。用户关闭某类 monitor 后该类事件一律拒绝，critical 天气也不能绕过关闭开关；在 monitor 已启用的前提下，仅 critical 天气可以绕过今日静默、安静时段、模式和主题 allow/block，新闻永不绕过；`conservative` 也只允许已启用的 critical 天气。

外界监测内部接口继续位于 `/config/proactive/**` 并使用 server-secret：

- `POST /config/proactive/monitors/claim`：每次最多领取 100 条到期任务，只选择 15 分钟内有设备探测的记录；数据库以 owner 和唯一 token 做 CAS，租约固定 120 秒，多实例只能有一个领取者成功。候选到期、活跃窗口、租约生成和 CAS 均以 MySQL `CURRENT_TIMESTAMP` 为唯一权威时钟，不使用各 JVM 墙钟。每条 `MonitorTask` 同时携带 manager-api 权威解析的 `weather_location/weather_location_error` 和 `news_sources/news_sources_error`；新闻插件未配置、设备未绑定智能体，或 `news_sources` 缺失/null/纯空白时使用“澎湃新闻、百度热搜、财联社”默认值且错误为空，只有非字符串、非法分隔、超限、重复键或多根 JSON 等畸形配置才返回空列表和明确错误。worker 不从天气 baseline 猜城市，也不自行读取智能体私有插件配置。
- `POST /config/proactive/monitors/complete`：只有数据库当前时间仍早于租约截止且匹配 owner/token 才能更新 state、成功时间、下次检查时间和错误码并释放租约；探测时间、活跃窗口和下次调度均以 MySQL 当前时间计算。用户 PUT 任一配置会立即使该 monitor 的现有租约失效，旧 worker 不得覆盖新配置对应的 state 或调度。状态 JSON 有大小上限并采用分类型白名单：两类顶层只允许 `schema_version`、字符串数组 `fingerprints` 和 `detection_status`；`detection_status` 只允许 ISO 时间 `last_event_at/cooldown_until`、字符串数组 `active_warning_ids/active_hazards`、字符串 `last_cluster_id`。仅 WEATHER 可额外保存 `baseline`，其顶层只允许 `captured_at/location_id/hourly/warning_ids/hazards`；`hourly` 元素只允许 `forecast_time/temp_c/weather_code/wind_speed_kmh/precip_mm/pop_pct`，`hazards` 元素只允许 `type/severity/window_start/window_end`。NEWS 禁止 `baseline`，所有层级未知字段和错误类型均明确拒绝。
- `GET /config/proactive/monitor-events/{eventId}?mac_address=...`：按 MAC 与 event ID 读取权威天气或新闻事件；投递仍复用既有 180 秒 `/events/{eventId}/claim` 接口。
- `POST /config/proactive/classifier/evaluate`：仅接受有界的新闻标题、来源和事实，使用全局独立分类模型，禁止回退设备智能体模型。固定分类契约使用 system 消息，序列化候选只作为独立的不可信 user JSON 数据；候选中的任何指令都不得改变角色或输出契约。模型只能返回一个完整 JSON 根对象，根对象后到 EOF 之间只能有空白；每个候选结果严格包含 `index/is_major/category/severity/confidence/spoken_summary/facts`，其中 `severity` 仅允许 `low/medium/high/critical`。manager-api 校验 JSON 结构、整数且不越界的索引、索引完整性和字段范围，并只返回重新序列化的规范 JSON，绝不透传原始模型文本或尾随推理内容。worker 只有在 `is_major=true`、`severity=high|critical` 且 `confidence>=0.85` 时才可创建新闻事件。

超级管理员通过 `GET|PUT /proactive/classifier/model` 读取或保存独立 LLM model id，并通过 `POST /proactive/classifier/model/test` 检查可用性。未配置、非 LLM、未启用或缺少必要连接配置时明确返回不可用；任何接口都不得返回模型密钥。

manager-web 在设备管理列表的单台设备操作区提供“主动助理”入口，使用同一弹窗分为设置、外界监测、事件审计和习惯四个区域：

- 设置区可修改主动程度、每日上限、安静时段及主题 allow/block，并可启用“今日静默”。表单必须执行与 manager-api 相同的模式额度、安静时段成对及主题互斥校验。
- 事件区支持按主题、事件类型和投递状态筛选及分页，只展示结构化的 `reason`、`delivery_status`、`outcome` 和时间，不展示 payload 或自由推理内容。
- 习惯区展示受控习惯类型、证据次数、状态和最近观察时间，并允许删除本人设备的记录。
- 外界监测区严格使用 `GET|PUT /device/proactive/monitors/{deviceId}`。常用区提供天气/新闻开关、均衡（30/10 分钟）/及时（10/5 分钟）/省资源（60/30 分钟）档位，以及继承地点、运行状态、上次成功、下次执行、最近错误和设备最近探测时间。继承地点只读取响应顶层权威 `weather_location`；该值由 manager-api 直接解析设备绑定智能体的 `get_weather.param_info.default_location`，不依赖首次监测基线。`weather_location_error` 使用受控错误码区分未绑定智能体、插件缺失/重复、配置无效和默认城市缺失/无效；Web 显示明确配置错误且不猜测城市。worker 后续的城市解析或天气 API 权限错误继续显示在 `weather.last_error_code`，与静态配置错误分离。
- 外界监测高级区直接映射严格 DTO：天气间隔、灾害类型、阈值和 `minimum_warning_severity`；新闻间隔、来源、类别、置信度和主题冷却。官方预警级别严格使用和风词表 `minor/moderate/severe/extreme`，默认最低 `moderate`；来源/类别为空表示沿用服务端继承和默认规则。保存前执行与 manager-api 相同的整数范围、列表、枚举、温度上下界及置信度校验；提交体不携带 DTO 之外的字段。
- 外界监测读取和保存使用独立请求通道；切换设备、关闭弹窗或同通道新请求后旧响应失效。首次读取失败清空不可信状态并禁止保存，保存失败保留当前表单以便重试。普通设备所有者直接读取响应顶层脱敏 `classifier:{configured,available,error}` 状态；该对象不包含 `model_id`、provider 或凭据。设备弹窗不调用超级管理员分类模型接口，也不得显示为已回退设备模型。
- 弹窗关闭、切换设备或同一通道发起新请求后，旧响应必须失效；偏好保存只能使用已成功加载且仍为当前设备的 `device_id`。保存失败保留当前表单以便重试，首次加载失败则清空不可信状态并禁用写操作。
- 弹窗宽度受视口限制，筛选项可换行，表格在窄屏下允许横向滚动，确保移动端仍可访问主要操作。

超级管理员的桌面“参数管理”页提供“外界新闻分类模型”专用卡片。模型选项只读取 LLM 列表，保存和连通性测试分别调用 `PUT /proactive/classifier/model` 与 `POST /proactive/classifier/model/test`；未配置或不可用时显示错误，并明确说明新闻监测不会回退设备智能体模型。该首期页面不修改 manager-mobile。

偏好默认模式为 `aggressive`、`daily_limit=0`，表示普通主动发言不受每日总额度限制，且积极模式不能配置成有限次数；没有默认安静时段。`active` 未显式给出 `daily_limit` 时为 5，允许用户在 1 至 5 内调低；`conservative` 固定为 1，`today_silent` 为 0。进入当日静默会同时保留 `previous_mode`、`previous_daily_limit` 和次日恢复时间；读取偏好时若静默已到期，manager-api 原子、完整地恢复原模式与原每日上限并递增 `version`。存量 `aggressive` 的 1 至 5 会以模式、旧额度和版本为条件做窄字段 CAS 规范化为 0，再权威重读，避免覆盖并发偏好更新；存量 `active` 的 1 至 3 保持原值。安静时段必须同时给出 `quiet_start`、`quiet_end` 且不能相同。

`conservative` 只执行关键事件属于设备端或服务端的策略执行职责；manager-api 只持久化偏好与完整事件审计，不在写入审计事件时按模式过滤。内部按 MAC 操作时必须且只能匹配一个现有设备；重复 MAC 会明确报错，不会任取其中一条记录。

所有接口枚举使用小写值。主题仅允许 `reminder`、`calendar`、`weather`、`news`、`music`、`health`、`habit`、`system`，事件类型增加 `news_alert`。事件 payload 只允许 `title`、`message`、`reference_id`、`scheduled_at`、`action`、`source`，每个值必须是非空字符串，`scheduled_at` 必须是 ISO 本地日期时间。习惯 payload 只允许 `description`、`suggested_mode`、`suggested_time`、`topic`：`description` 必须是非空短文本，`topic` 必须是上述小写主题，`suggested_mode` 只允许 `conservative`、`active`、`aggressive`，`suggested_time` 必须为 `HH:mm`。任一 payload 序列化后最多 512 字节，不接收也不保存自由推理链。响应将 JSON 字段解析为对象，不返回数据库中的原始 JSON 文本。

### 服务端执行契约

连接建立后，服务端按设备 MAC 异步读取偏好。manager-api 不可用时，critical 故障与恢复通知仍直接投递；其他建议使用 `active`、每日 5 次、无默认安静时段的本地安全值。`conservative` 只允许 critical，`today_silent` 屏蔽非 critical；`active` 同时受日上限、主题冷却、安静时段和 allow/block 主题约束。`aggressive` 仅绕过每日总额度，主题冷却、安静时段、allow/block、过期、连接和投递安全规则均不变；每个 topic 在领取时按自身冷却时长记录截止时间，每次领取前清理已到期项，跨日只重置每日已用次数，不清除仍在有效期内的主题冷却；critical 原语义不变。同设备并发领取在进程内原子完成。

建连 GET 偏好的成功或失败结果都必须校验当前连接的偏好修改代次；设备工具已在此期间成功修改偏好时，过期 GET 既不得覆盖新值，也不得因请求失败把新值重置为本地默认。

新主动事件按 `pending → claimed → delivered/failed` 异步审计；普通非竞争事件可从 `pending` 直接进入 `delivered/failed`。`/status` 禁止直接写 `claimed`，claimed 只有匹配 token 才能进入终态；终态不得回退，`delivered` 只允许保持同状态更新 outcome。`delivered` 只能在音频发送且设备播放完成信号返回后写入；合成、发送、断连、用户打断、句子被替换或等待超时都写入 `failed`。事件创建或初始状态更新失败时，审计任务显式返回失败，后续 outcome 不得越过失败任务继续回写。后台日志只记录异常类型，不记录 payload、`label` 或 `details`。

所有 TTS provider 都必须转发消息自身携带的 completion，包括工具提示使用的 `MIDDLE` 文本段；abort、旧句子、合成或发送异常必须完成为失败且同一 completion 只完成一次。日程完成邀请、天气行动句、习惯建议、深夜音乐建议和队列结束建议都在播报前创建审计生命周期并绑定真实 completion。音乐服务连续失败建议因当前普通工具结果链没有独立播放完成句柄，只创建 `pending` 审计，不得把未知结果写成 `delivered` 或 `failed`。

统一事件必须满足 `expires_at` 晚于服务端当前 Unix 秒；已过期事件在审计和播报前拒绝。同连接事件 ID 使用容量 256 的 FIFO 去重集合，达到上限时只逐出最早项，不得整表清空；跨连接则在播报前利用 manager-api 幂等创建结果，已为 `delivered` 的事件不得重播。主动 manager-api 请求采用 0.5 秒短超时并最多重试一次。偏好、习惯、通知处理和审计任务均归属当前连接，关闭时等待审计写入失败终态并取消其余任务，任务不得继续访问旧连接。

`notifications/device/health` 严格接受 `version=1` 与统一字段 `event_id/topic/priority/reason/created_at/expires_at/dedupe_key/requires_response`。`event_id` 和 `dedupe_key` 长度为 1–96，时间是 Unix 秒整数。`kind` 仅允许 `network_flapping/time_unsynchronized/ota_update_available/audio_decode_failed`；`severity` 仅允许 `info/warning/critical`，并映射 `priority=normal/high/critical`。未恢复的 critical 使用 `topic=health_critical`，其他使用 `topic=health`；故障/恢复的 reason 固定为 `device health`/`device health recovered`，`requires_response=false`。

`details` 是严格单键对象：network 仅 `disconnects_in_5m` 数字字符串，time 仅 `uptime_seconds` 数字字符串，OTA 仅 `version` 安全短字符串，audio 仅 `error_code` 整数样字符串；恢复可使用空对象。播报只使用 kind/recovered 固定安全模板。critical 故障和恢复不受普通策略抑制，warning/info 仍受偏好限制。

服务端仅在真实成功的日程创建、每日简报触发及用户发起的网易云 `category/playlist/favorites` 播放成功后观察受控习惯。证据达到 3 次后，只在策略允许时用确定性事件 ID 和固定短模板建议一次，不用 LLM 推断。22:30–01:00 用户主动开始音乐后每晚最多一次询问是否换轻音乐或设停止提醒。天气简报只对雨/高温/降温明确关键词追加一句固定行动建议，无地点或无数据不建议。

习惯候选必须先用稳定审计字段幂等写入并读取已有投递状态；已为 `delivered` 时直接跳过，不得消耗当日预算。只有确认尚未投递后才能原子领取建议机会。

`self.proactive.*` 由设备权威执行；成功结果的 `data` 立即更新当前连接并后台 PUT manager-api，失败重试一次且不改变设备成功播报。`self.schedule.complete_recent/follow_up/dismiss_follow_up` 成功后分别更新当前 follow-up outcome 为 `completed/acknowledged/dismissed`；成功结果 `data.source_id` 必须为正整数且与当前 follow-up 的 `source_id` 完全一致，否则拒绝回写。

每日简报的雨天建议先识别“没有雨、无雨、不下雨、不会下雨、未下雨、雨已停”等否定语义；命中否定时，即使文本包含“雨”字也不得追加带伞建议。
