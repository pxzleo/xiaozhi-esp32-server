# 设备本地日程提醒接入

天气/新闻主动简报使用独立白名单通知，见 [proactive-assistant-integration.md](proactive-assistant-integration.md)。

本文记录小智服务端与设备本地日程提醒的边界和通知契约。设备端的持久化、定时触发、停止与稍后提醒行为，以设备项目的 `docs/schedule-reminder.md` 为配套规范。

## 架构边界

- 日程由设备本地保存和调度；服务端不新增数据库、HTTP 接口、manager-api 或 manager-web 页面。
- 服务端通过设备动态上报的 MCP 工具描述，将 `self.schedule.create/list/delete/clear/stop/snooze` 暴露给主 LLM。工具名会按既有规则转换为下划线形式，设备描述中的自然表达示例和缺失信息追问规则保持可见。
- 工具执行结果以设备返回为准。设备返回 `action=RESPONSE` 时，服务端直接播报 `response`，不再交给第二次 LLM 改写；设备报错时必须按失败处理，不能声称创建成功。
- 工具调用前提示“我来处理一下”只用于 `web_search` 和 `search_from_ragflow`，日程工具不播放该提示。

## 触发通知契约

设备端先以至少 80% 的临时音量完整播放两次本地提示音，确认播放队列排空后才发送通知。若用户此前已退出对话、音频通道已关闭，设备必须先主动重新建立通道，建链成功后再发送通知。服务端收到有效通知后直接播报提醒内容；设备以本次提醒的 TTS `start/stop` 为边界，在播报完成后自动进入默认收听模式。设备发送通知后最多等待 15 秒让服务端开始 TTS，超时会解除交付占用并恢复临时音量，避免离线状态阻塞后续任务或误关联普通对话的 TTS。服务端不得要求设备在本地提示音尚未完成时提前发送通知。

闹铃和普通提醒触发后不恢复此前音乐。每日简报使用独立通知契约；若它打断了正在播放的网易云歌曲，服务端会在简报 TTS 完整结束后恢复原歌曲，详见 `proactive-assistant-integration.md`。

积极主动模式下，普通提醒可在固定提醒内容后追加一句简短完成确认并进入收听；闹铃保持原播报，不询问是否完成。设备创建第二个同类型、同内容、同一时刻但不同日期的单次任务时，会在成功确认后建议改成重复任务。模糊的“晚点做”表达必须先询问是否需要提醒和具体时间。

设备在本地到点后，通过已有设备 MCP WebSocket 发送：

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/schedule/triggered",
  "params": {
    "version": 1,
    "id": 7,
    "kind": "reminder",
    "label": "喝水",
    "triggered_at": "2026-08-07T14:30:00",
    "speak": true
  }
}
```

服务端仅接受同时满足以下条件的通知：

- `params` 是对象；
- `version` 是非布尔整数且严格等于 `1`；
- `id` 是非布尔正整数；
- `kind` 严格等于 `reminder` 或 `alarm`；
- `label` 去除首尾空白后包含 1 至 80 个 Unicode 字符；
- `triggered_at` 是有效本地日期时间，格式严格为 `YYYY-MM-DDTHH:MM:SS`，不带时区；
- `speak` 严格为 `true`。

有效的普通提醒直接生成并播报 `提醒你：{label}`，有效的闹铃直接生成并播报 `闹铃时间到了：{label}`，同时按普通助手回复写入对话记录，不调用 LLM。主动通知在把 `FIRST → TEXT → LAST` 放入 TTS 队列前必须先发送 `tts state=start` 并同步 `client_is_speaking=true`，设备端协议顺序严格为 `start → sentence_start → 音频 → stop`；缺少 start 时设备仍处于 Idle，会丢弃随后到达的音频。新连接的 TTS 允许继续后台初始化，但服务端必须等待该连接的 TTS 对象完成音频通道启动后才创建提醒语音，最长等待 2 秒；加上取消旧轮次和当前 IndexTTS 的首包超时后，服务端最坏启动预算不超过 13 秒，须早于设备端 15 秒等待上限。超时或初始化失败时记录不含提醒正文的明确错误，不得访问未就绪的 TTS。无效或未知通知只记录不含 `label` 正文的安全日志，并拒绝播报。

## 并发与打断语义

设备触发提醒前会先发送 `abort`，因此服务端开始主动通知轮次时会清除旧轮次留下的 `client_abort`，分配新的 `sentence_id`，再取消旧 LLM。主动播报复用统一的 `FIRST → 单句文本 → LAST` TTS 序列和对话记录逻辑，网易云状态通知也使用同一处理器。

等待 TTS 就绪以及取消旧 LLM 的期间，用户输入始终优先：服务端在 TTS 就绪后会重新核对接收通知时的 `sentence_id` 和中断代次；如果新的用户轮次改变了任一状态，或随后再次设置 `client_abort=true`，该提醒立即失效，不得写入对话或开始播报。这一保护避免旧通知覆盖新用户请求。

发送 `tts start` 本身也是异步边界；发送完成后必须再次核对连接、句子 ID、中断代次和 `client_abort`。每个 start 同时取得递增的 TTS 控制代次；旧通知补发 stop 前必须在音频等待完成后再次核对该代次，新 start 已取得所有权时禁止旧 stop 清除或打断新轮次。若通知在 start 边界被新轮次替代，服务端应在没有新 TTS owner 时补发 stop 清理刚建立的设备播报状态，不得继续入队旧通知。若 start 成功后构造或入队 TTS 句子异常，也必须尽力发送带所有权校验的 stop、清除本通知持有的 speaking 状态，再把原异常交给后台任务入口记录。

MCP 消息仍由后台任务处理，但任务入口必须捕获并记录异常类型，禁止再产生无人获取的后台任务异常；该异常日志只记录异常类型，不附带 MCP payload 或提醒正文。连接关闭后，等待中的通知必须立即失效，不得再写入 TTS 队列或对话记录。

## 设备职责

设备负责本地时间解释、日程持久化、到点触发、重启恢复、停止和稍后提醒。服务端只负责把动态工具提供给主 LLM、转发工具调用、处理权威设备响应，以及校验并播报到点通知。设备与服务端必须共同遵守上述版本化通知契约。

## 完成跟进通知

设备对已触发的普通提醒发起完成确认时，发送 `notifications/schedule/follow_up`：

```json
{"jsonrpc":"2.0","method":"notifications/schedule/follow_up","params":{"version":1,"event_id":"follow-up-7-1","topic":"follow_up","priority":"normal","reason":"schedule follow up","created_at":1786170600,"expires_at":1786174200,"dedupe_key":"schedule-follow-up-7-1","requires_response":true,"follow_up":true,"source_id":7,"label":"喝水","speak":true}}
```

请求不得有额外字段；`source_id` 是正整数，`label` 去空白后为 1–80 个 Unicode 字符，`topic=follow_up`、`priority=normal`、`reason="schedule follow up"`、`follow_up=true`、`requires_response=true`、`speak=true`。服务端不调用 LLM，固定播报“刚才提醒的 `{label}` 完成了吗？”，并沿用现有 TTS 和自动收听链。同连接重复 `event_id` 只处理一次；65–96 字符的设备事件 ID 会确定性映射为不超过 64 字符的 manager 审计 ID。无效通知日志不包含 `label`。

跟进事件按统一主动事件契约审计。设备 `self.schedule.complete_recent/follow_up/dismiss_follow_up` 成功后，服务端分别将当前事件 outcome 更新为 `completed/acknowledged/dismissed`。普通闹铃/提醒仍不恢复音乐，每日简报的独立恢复逻辑不受影响。
