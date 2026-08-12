# Android 小智 M1 服务端协议

本文记录当前仓库已经实现的手机实例绑定和实时助手协议。它不是规划接口清单。

## 1. 边界与复用

- manager-api 负责账号登录、智能体所有权、手机实例和可撤销凭据。
- Python WebSocket 负责现有实时会话。手机与硬件共用 `ConnectionHandler`、Opus/ASR、LLM、TTS、打断、聊天记录和主动事件配置，不存在第二套会话引擎。
- 文字消息通过同一 WebSocket 的 `listen/detect` 帧进入 `startToChat`。当前不提供 `POST /mobile/conversations/{id}/messages`，因为 manager-api 无法安全进入 Python 进程内的连续会话；客户端不得把 404 当成成功。
- 客户端输入的 `text` 只表示用户消息。协议不接受 `tts_text`、`assistant_text`、`play_text` 等客户端指定播报正文的字段；所有未知字段都会被拒绝。

## 2. 绑定与撤销

`POST /mobile/devices/bind` 使用智控台账号 Bearer token，并要求普通用户权限。

请求：

```json
{
  "version": 1,
  "installation_id": "123e4567-e89b-12d3-a456-426614174000",
  "platform": "android",
  "app_version": "0.1.0",
  "agent_id": "已归当前账号所有的智能体ID",
  "capabilities": ["text_chat", "voice_session"]
}
```

`installation_id` 必须是客户端随机、可重置的 UUID。不得使用或上传 IMEI、Android ID、序列号、MAC 等硬件稳定标识。允许能力只有 `text_chat`、`voice_session`、`notification_gateway`、`location_gateway`。

成功响应的 `data`：

```json
{
  "version": 1,
  "mobile_instance_id": "mob_0123456789abcdef0123456789abcdef",
  "access_token": "仅本次响应返回的高熵凭据",
  "credential_version": 1,
  "websocket_url": "wss://example.com/mobile/assistant",
  "websocket_path": "/mobile/assistant"
}
```

同一账号和 `installation_id` 再次绑定会校验新的 `agent_id` 所有权、复用手机实例并轮换凭据。数据库只保存凭据的 SHA-256，不保存明文。

`DELETE /mobile/devices/{mobile_instance_id}?credential_version={绑定响应中的版本}` 同样使用账号 Bearer token。只能撤销当前账号的实例，并通过版本 CAS 防止旧撤销请求误删已经轮换的新凭据；版本过期返回 HTTP 409。撤销后旧凭据的下一次鉴权失败，已建立连接也会在后续控制帧或最多 5 秒一轮的持续音频输入上复验失败并关闭。清除应用数据后客户端应生成新的 `installation_id`。

## 3. WebSocket 握手

连接绑定响应中的 `websocket_url`，必须发送：

```text
Authorization: Bearer <access_token>
Mobile-Instance-Id: mob_...
Client-Id: <installation_id>
Mobile-Protocol-Version: 1
Mobile-Credential-Version: <credential_version>
Mobile-Capabilities: text_chat,voice_session
```

能力不得重复或包含未知值，并且必须是绑定时能力的子集。Python 服务使用内部 server secret 调用 `POST /config/mobile/instances/{id}/authorize`；此接口不是手机公开接口。鉴权失败发送 `UNAUTHORIZED` 并以 4401 关闭；协议错误以 4400 关闭；鉴权服务不可用以 1011 关闭。凭据、正文和请求签名不写日志。

手机握手不得携带 `Device-Id`；它不是硬件设备，鉴权成功后由服务端把 `mobile_instance_id` 映射到现有会话引擎所需的内部设备身份。
`mob_` 命名空间由手机实例保留，即使普通设备鉴权关闭，普通 `/xiaozhi/v1` 路径也会无条件拒绝该身份，防止绕过手机凭据、版本和能力校验。握手成功后长期凭据仅保存在连接私有鉴权上下文，进入通用连接处理器前会从请求头移除并脱敏，连接日志不记录它。

## 4. 控制帧

手机在扬声器外放且声明系统 AEC 已真实启用时，服务端仍允许用户抢话，但不会在第一个 VAD 起点立即截断播报。上行必须连续命中约 360 ms（6 个 60 ms 音频包）后才确认抢话并中止当前 TTS；不足确认窗即结束的短促残余视为回声并在进入 ASR 前丢弃。该确认窗只作用于手机播报期间，不改变音箱设备、非播报阶段或手动收音模式的既有 VAD/AEC 参数。

所有手机文本帧都是 UTF-8 JSON 对象，必须带 `version: 1`，最大 8192 字节，不允许尾随 JSON 和未知字段。音频二进制帧最大 16384 字节。

首个业务帧必须且只能是一次 `hello`；在它之前发送音频、`listen`、`abort` 或 `ping`，以及重复发送 `hello`，都会以 4400 关闭。服务端只有在账号绑定配置、会话组件以及 ASR/TTS 音频通道全部就绪后才处理手机业务帧；10 秒内未就绪以 1011 关闭，不能把消息标为成功。

首帧：

```json
{
  "type": "hello",
  "version": 1,
  "transport": "websocket",
  "audio_params": {
    "format": "opus",
    "sample_rate": 24000,
    "channels": 1,
    "frame_duration": 60
  },
  "features": {"aec": true},
  "message_id": "可选，1到64位 ASCII 字母、数字、点、下划线、冒号或连字符"
}
```

这里的 `audio_params` 与现有服务端 welcome/TTS 输出协商一致：Opus、24 kHz、单声道、60 ms。客户端上传的 Opus 语音沿用现有服务端 16 kHz 单声道解码路径。

拾音状态：

```json
{"type":"listen","version":1,"state":"start","mode":"auto"}
{"type":"listen","version":1,"state":"stop"}
```

文字消息：

```json
{
  "type": "listen",
  "version": 1,
  "state": "detect",
  "text": "用户输入，去除首尾空白后不可为空，最多2000字符",
  "message_id": "必填，最多64字符"
}
```

服务端先用 `(mobile_instance_id, message_id)` 持久化领取，等待真实绑定初始化完成，再调用现有助手文字处理器；只有处理器成功返回且收件箱持久化为 `ACCEPTED` 后才返回：

```json
{"type":"message_ack","version":1,"message_id":"msg_01","status":"accepted","session_id":"..."}
```

同一条已确认消息跨断线重发返回 `status: "duplicate"`，不会再次进入助手引擎。处理中重发返回 `MESSAGE_IN_PROGRESS`，客户端应保留待发项并退避重试。只有收到 `accepted` 或 `duplicate` 后客户端才可删除本地待发项；鉴权、助手处理或持久确认不可用时不能把消息标为成功。未确认消息采用带 30 秒租约的恢复语义，因此客户端必须复用原 `message_id` 重试。

内部接口均由 Python 服务使用 server secret 调用，不向手机开放：

- `POST /config/mobile/instances/{id}/authorize`：校验令牌、凭据版本、安装实例和能力子集，同时用于在线撤销复验。
- `POST /config/mobile/instances/{id}/messages/{message_id}/claim`：领取或查询幂等状态。
- `POST .../renew`：助手处理期间每 10 秒延长 30 秒租约。
- `POST .../complete`：助手处理成功后以精确领取令牌持久确认；一旦进入处理器，异常结果不明时也不会释放领取。

打断和保活：

```json
{"type":"abort","version":1,"reason":"user_speech","message_id":"可选"}
{"type":"ping","version":1,"message_id":"可选"}
```

服务端输出继续使用现有 `hello`、`stt`、`tts`、`abort`、二进制 Opus 帧：`hello.session_id` 表示会话已开始；`stt` 是确认后的用户文字/转写；`tts` 的 `start`、`sentence_start`、`sentence_end`、`stop` 覆盖助手文字和 TTS 生命周期；打断复用现有 `abort` 语义。

## 5. 已验证与未验证

自动化测试覆盖 DTO 未知字段/尾随 JSON、版本和能力白名单、账号-智能体所有权、凭据哈希校验、撤销判定、握手身份映射、文字帧、大小和枚举错误。尚未完成数据库真实迁移、服务重启、真实 Android/网络/TLS、录音播放和端到端 TTS 验收。
