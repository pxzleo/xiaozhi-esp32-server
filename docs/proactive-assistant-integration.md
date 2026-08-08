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

积极主动模式只在已有可靠上下文时给出短建议，不允许模型凭空制造提醒。非紧急主动建议按设备标识共享每日 3 次预算，并为同一主题设置冷却时间；服务进程重启后预算重新计算。当前没有用户作息数据，因此不擅自设置默认安静时段。

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
- `PUT /config/proactive/events/{eventId}/status`：请求体必须带 `mac_address`，按设备与事件共同更新投递状态。
- `POST /config/proactive/habits/observe`：按 `(device_id, habit_type, habit_key)` 原子累加证据；证据达到 3 次后进入候选。
- `GET /config/proactive/habits/candidates?mac_address=...`：列出尚未接受或忽略的建议候选。

用户接口：

- `GET /device/proactive/preferences`、`GET|PUT /device/proactive/preferences/{deviceId}`：列出本人设备或管理单个设备偏好。
- `PUT /device/proactive/preferences/{deviceId}/today-silent`：静默至服务端所在时区的次日零点。
- `GET /device/proactive/events`：分页参数为 `page`（1 至 1000）和 `limit`（1 至 100），可选 `device_id`、`topic`、`delivery_status`、`event_type` 过滤；未给 `device_id` 时只查询本人全部绑定设备。
- `GET /device/proactive/habits`、`DELETE /device/proactive/habits/{habitId}`：列出本人设备的习惯或删除指定候选；列表可选 `device_id`。

manager-web 在设备管理列表的单台设备操作区提供“主动助理”入口，使用同一弹窗分为设置、事件审计和习惯三个区域：

- 设置区可修改主动程度、每日上限、安静时段及主题 allow/block，并可启用“今日静默”。表单必须执行与 manager-api 相同的模式额度、安静时段成对及主题互斥校验。
- 事件区支持按主题、事件类型和投递状态筛选及分页，只展示结构化的 `reason`、`delivery_status`、`outcome` 和时间，不展示 payload 或自由推理内容。
- 习惯区展示受控习惯类型、证据次数、状态和最近观察时间，并允许删除本人设备的记录。
- 弹窗关闭、切换设备或同一通道发起新请求后，旧响应必须失效；偏好保存只能使用已成功加载且仍为当前设备的 `device_id`。保存失败保留当前表单以便重试，首次加载失败则清空不可信状态并禁用写操作。
- 弹窗宽度受视口限制，筛选项可换行，表格在窄屏下允许横向滚动，确保移动端仍可访问主要操作。

偏好默认模式为 `aggressive`、每日上限 5 次且没有默认安静时段。`active` 未显式给出 `daily_limit` 时为 3，`conservative` 为 1，`today_silent` 为 0。进入当日静默会同时保留 `previous_mode`、`previous_daily_limit` 和次日恢复时间；读取偏好时若静默已到期，manager-api 原子、完整地恢复原模式与原每日上限并递增 `version`。安静时段必须同时给出 `quiet_start`、`quiet_end` 且不能相同。

`conservative` 只执行关键事件属于设备端或服务端的策略执行职责；manager-api 只持久化偏好与完整事件审计，不在写入审计事件时按模式过滤。内部按 MAC 操作时必须且只能匹配一个现有设备；重复 MAC 会明确报错，不会任取其中一条记录。

所有接口枚举使用小写值。主题仅允许 `reminder`、`calendar`、`weather`、`music`、`health`、`habit`、`system`。事件 payload 只允许 `title`、`message`、`reference_id`、`scheduled_at`、`action`、`source`，每个值必须是非空字符串，`scheduled_at` 必须是 ISO 本地日期时间。习惯 payload 只允许 `description`、`suggested_mode`、`suggested_time`、`topic`：`description` 必须是非空短文本，`topic` 必须是上述小写主题，`suggested_mode` 只允许 `conservative`、`active`、`aggressive`，`suggested_time` 必须为 `HH:mm`。任一 payload 序列化后最多 512 字节，不接收也不保存自由推理链。响应将 JSON 字段解析为对象，不返回数据库中的原始 JSON 文本。

### 服务端执行契约

连接建立后，服务端按设备 MAC 异步读取偏好。manager-api 不可用时，critical 故障与恢复通知仍直接投递；其他建议使用 `active`、每日 3 次、无默认安静时段的本地安全值。`conservative` 只允许 critical，`today_silent` 屏蔽非 critical；其他模式同时受日上限、主题冷却、安静时段和 allow/block 主题约束。同设备并发领取在进程内原子完成。

建连 GET 偏好的成功或失败结果都必须校验当前连接的偏好修改代次；设备工具已在此期间成功修改偏好时，过期 GET 既不得覆盖新值，也不得因请求失败把新值重置为本地默认。

新主动事件按 `pending → delivered/failed` 异步审计。`delivered` 只能在音频发送且设备播放完成信号返回后写入；合成、发送、断连、用户打断、句子被替换或等待超时都写入 `failed`。事件创建或初始状态更新失败时，审计任务显式返回失败，后续 outcome 不得越过失败任务继续回写。审计失败不阻断 critical 播报，后台日志只记录异常类型，不记录 payload、`label` 或 `details`。

所有 TTS provider 都必须转发消息自身携带的 completion，包括工具提示使用的 `MIDDLE` 文本段；abort、旧句子、合成或发送异常必须完成为失败且同一 completion 只完成一次。日程完成邀请、天气行动句、习惯建议、深夜音乐建议和队列结束建议都在播报前创建审计生命周期并绑定真实 completion。音乐服务连续失败建议因当前普通工具结果链没有独立播放完成句柄，只能明确审计为 `failed`，不得提前写 `delivered`。

统一事件必须满足 `expires_at` 晚于服务端当前 Unix 秒；已过期事件在审计和播报前拒绝。同连接事件 ID 使用容量 256 的 FIFO 去重集合，达到上限时只逐出最早项，不得整表清空；跨连接则在播报前利用 manager-api 幂等创建结果，已为 `delivered` 的事件不得重播。主动 manager-api 请求采用 0.5 秒短超时并最多重试一次。偏好、习惯、通知处理和审计任务均归属当前连接，关闭时等待审计写入失败终态并取消其余任务，任务不得继续访问旧连接。

`notifications/device/health` 严格接受 `version=1` 与统一字段 `event_id/topic/priority/reason/created_at/expires_at/dedupe_key/requires_response`。`event_id` 和 `dedupe_key` 长度为 1–96，时间是 Unix 秒整数。`kind` 仅允许 `network_flapping/time_unsynchronized/ota_update_available/audio_decode_failed`；`severity` 仅允许 `info/warning/critical`，并映射 `priority=normal/high/critical`。未恢复的 critical 使用 `topic=health_critical`，其他使用 `topic=health`；故障/恢复的 reason 固定为 `device health`/`device health recovered`，`requires_response=false`。

`details` 是严格单键对象：network 仅 `disconnects_in_5m` 数字字符串，time 仅 `uptime_seconds` 数字字符串，OTA 仅 `version` 安全短字符串，audio 仅 `error_code` 整数样字符串；恢复可使用空对象。播报只使用 kind/recovered 固定安全模板。critical 故障和恢复不受普通策略抑制，warning/info 仍受偏好限制。

服务端仅在真实成功的日程创建、每日简报触发及用户发起的网易云 `category/playlist/favorites` 播放成功后观察受控习惯。证据达到 3 次后，只在策略允许时用确定性事件 ID 和固定短模板建议一次，不用 LLM 推断。22:30–01:00 用户主动开始音乐后每晚最多一次询问是否换轻音乐或设停止提醒。天气简报只对雨/高温/降温明确关键词追加一句固定行动建议，无地点或无数据不建议。

习惯候选必须先用稳定审计字段幂等写入并读取已有投递状态；已为 `delivered` 时直接跳过，不得消耗当日预算。只有确认尚未投递后才能原子领取建议机会。

`self.proactive.*` 由设备权威执行；成功结果的 `data` 立即更新当前连接并后台 PUT manager-api，失败重试一次且不改变设备成功播报。`self.schedule.complete_recent/follow_up/dismiss_follow_up` 成功后分别更新当前 follow-up outcome 为 `completed/acknowledged/dismissed`；成功结果 `data.source_id` 必须为正整数且与当前 follow-up 的 `source_id` 完全一致，否则拒绝回写。

每日简报的雨天建议先识别“没有雨、无雨、不下雨、不会下雨、未下雨、雨已停”等否定语义；命中否定时，即使文本包含“雨”字也不得追加带伞建议。
