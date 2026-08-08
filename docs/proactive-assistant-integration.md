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

偏好默认模式为 `aggressive`、每日上限 5 次且没有默认安静时段。`active` 未显式给出 `daily_limit` 时为 3，`conservative` 为 1，`today_silent` 为 0。进入当日静默会同时保留 `previous_mode`、`previous_daily_limit` 和次日恢复时间；读取偏好时若静默已到期，manager-api 原子、完整地恢复原模式与原每日上限并递增 `version`。安静时段必须同时给出 `quiet_start`、`quiet_end` 且不能相同。

`conservative` 只执行关键事件属于设备端或服务端的策略执行职责；manager-api 只持久化偏好与完整事件审计，不在写入审计事件时按模式过滤。内部按 MAC 操作时必须且只能匹配一个现有设备；重复 MAC 会明确报错，不会任取其中一条记录。

所有接口枚举使用小写值。主题仅允许 `reminder`、`calendar`、`weather`、`music`、`health`、`habit`、`system`。事件 payload 只允许 `title`、`message`、`reference_id`、`scheduled_at`、`action`、`source`，每个值必须是非空字符串，`scheduled_at` 必须是 ISO 本地日期时间。习惯 payload 只允许 `description`、`suggested_mode`、`suggested_time`、`topic`：`description` 必须是非空短文本，`topic` 必须是上述小写主题，`suggested_mode` 只允许 `conservative`、`active`、`aggressive`，`suggested_time` 必须为 `HH:mm`。任一 payload 序列化后最多 512 字节，不接收也不保存自由推理链。响应将 JSON 字段解析为对象，不返回数据库中的原始 JSON 文本。
