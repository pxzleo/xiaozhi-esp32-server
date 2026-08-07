# 网易云音乐设备登录与播放

## 范围和外部依赖

网易云音乐以独立服务端插件提供，并与服务器音乐、Home Assistant 音乐互斥。登录凭证按设备隔离，不再存入智能体插件参数，也不在管理网页扫码。旧管理端二维码代理和前端辅助代码已删除，旧 `cookie` 参数也不会进入设备播放链。

播放提示中的歌手最多播报前两位；歌曲存在更多歌手时在第二位后追加“等”。该限制仅作用于语音提示，歌词页面的 `track.artists` 仍发送完整歌手列表。

本功能依赖自建的 [NeteaseCloudMusicApiEnhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced)。它是非官方接口，可能因上游接口、风控、地区或版权策略变化而失效，不能视为网易云官方或具有稳定性承诺的服务。建议只在可信网络部署并关闭解灰。

## 设备接口

基础路径为 `/device/netease`。所有请求都必须携带：

```http
Device-Id: 11:22:33:44:55:66
Client-Id: device-client-id
Authorization: Bearer <signature.timestamp>
```

签名复用现有 OTA/WebSocket 认证：使用 `server.secret` 对 `clientId|deviceId|timestamp` 做 HMAC-SHA256，再进行无填充 URL-safe Base64 编码。时间戳单位为秒，有效期 30 天；过期或来自未来的 token 均拒绝。通过签名后，业务层仍会确认 `Device-Id` 已存在于绑定设备表 `ai_device.mac_address`。

这不是独立的设备持有证明：当前匿名 OTA 流程允许客户端按自报 MAC 获取 WebSocket token，因而能自报已知 MAC 的调用方可能取得同等设备接口凭据。这是现有 OTA/WebSocket 认证架构的阻塞项；在引入设备私钥、出厂证书或已绑定用户参与的挑战响应前，不应把该接口描述为强设备认证。此变更不会收紧匿名 OTA，也不会破坏未绑定设备的既有激活流程。

所有接口沿用项目统一响应结构：成功时 `code=0`，业务数据位于 `data`；失败时 `code` 非 0，错误说明位于 `msg`。设备端应优先依据 HTTP 状态、`code` 和下文状态枚举处理，不要匹配自由文本错误信息。

### 查询状态

```http
GET /device/netease/status
```

```json
{
  "code": 0,
  "data": {
    "status": "logged_in"
  }
}
```

状态只返回 `logged_in` 或 `logged_out`，与设备端 `GetLoginStatus` 契约一致。账号资料和凭证均不返回设备。

状态响应不会包含 Cookie、二维码 key 或二维码原文。

### 创建二维码会话

```http
POST /device/netease/sessions
```

```json
{
  "code": 0,
  "data": {
    "session_id": "b5d8bb6f-...",
    "qr_image_url": "http://192.168.100.149/xiaozhi/device/netease/sessions/b5d8bb6f-.../qr-image",
    "expires_in_ms": 300000,
    "poll_interval_ms": 2000
  }
}
```

会话有效期固定为 5 分钟，并绑定创建它的设备标识。同一设备只能有一个数据库活动槽：再次创建会复用已完成创建且尚未过期的会话；并发请求遇到尚在生成二维码的会话时返回明确的“正在创建，请稍后重试”。服务端不向设备返回 Data URL 或二维码原文。

二维码图片地址也要求相同的设备鉴权头：

```http
GET /device/netease/sessions/{sessionId}/qr-image
```

成功返回经完整解码校验的 `image/png` 或 `image/jpeg`，`Cache-Control: no-store`，图片最大 256 KiB。会话不属于当前设备、已终结或已过期时拒绝读取。

### 轮询二维码

```http
GET /device/netease/sessions/{sessionId}
```

```json
{"code":0,"data":{"status":"pending"}}
```

设备应按 `poll_interval_ms` 轮询。设备协议状态为 `pending`、`authorized`、`expired`、`cancelled`、`failed`。服务端内部映射为：上游 801/802 均返回 `pending`，800 返回 `expired`，803 返回 `authorized`；服务端终止但没有更具体上游状态时返回 `cancelled` 或 `failed`。临时网络错误返回失败响应，但不会终结仍有效的二维码会话，设备可保留页面继续轮询。

收到 803 后，服务端提取 Cookie、调用 `/user/account` 获取用户标识、昵称和头像；随后通过带活动状态及过期时间条件的数据库更新认领会话，并在同一数据库事务中保存加密凭证。若会话已过期、退出、被管理员撤销或因设备删除终止，条件更新失败且不会写入授权。801/802 同样使用条件更新，不能覆盖终态。终态重复轮询是幂等的。

当前代码按 NeteaseCloudMusicApiEnhanced 的既有契约向 `/login/qr/check` 发送 `noCookie=true`，并从 803 JSON 响应的 `cookie` 字段继续绑定链；该契约有 mock 单元测试，但本次未连接本地真实上游完成扫码，因此不能把“当前部署的上游一定仍返回 Cookie”视为已联调结论。

设备语音/屏幕提示映射：

- `pending`：继续显示专用二维码页；
- `authorized`：“网易云音乐登录成功”；
- `expired`：“二维码已过期，请重新获取”；
- `cancelled`：关闭二维码页并提示已取消；
- `failed`：“登录服务暂不可用，请稍后重试”。

### 退出登录

```http
POST /device/netease/logout
```

退出会先终止该设备全部活动二维码会话并清除二维码 key/内容，防止旧二维码在退出后重新绑定。已登录时再尝试上游 `/logout`，随后始终删除本地可用凭证。上游退出失败时本地仍安全撤销。

设备语音中的“关闭/隐藏网易云登录二维码”和“取消本次扫码”复用 `Logout` 工具：它会调用上述退出接口终止当前活动会话并关闭二维码页，不得通过降低屏幕亮度代替，也不得在未调用工具时仅口头声称已经关闭。

```json
{
  "code": 0,
  "data": {
    "status": "logged_out"
  }
}
```

状态只返回 `logged_out` 或 `already_logged_out`，与设备端 `Logout` 契约一致。

### 通用错误码

| HTTP 状态 | `code` | 含义 | 设备处理建议 |
| --- | ---: | --- | --- |
| 401 | 401 | 缺少设备头、令牌错误或令牌过期 | 重新获取现有 OTA/WebSocket token；不要继续轮询 |
| 200 | 403 | 管理员权限不足 | 管理端提示无权限 |
| 200 | 500 | 项目现有 `RenException` 业务失败，例如设备未绑定、会话不存在/错配、已登录时重复创建、上游创建失败 | 业务语音使用固定兜底提示，`msg` 仅用于诊断 |
| 200 | 10034 | 请求字段校验失败，例如管理撤销原因为空 | 修正请求字段后重试 |

二维码扫描进度、过期和退出幂等均通过成功响应中的小写 `status` 表达，不作为传输层错误处理。

### 异步语音通知

登录工具已经返回、设备随后轮询到终态时，通过现有设备 MCP WebSocket 发送：

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/netease_music/status",
  "params": {"message": "网易云音乐登录成功。", "speak": true}
}
```

会话服务仅接受上述固定 method；`params.message` 必须是 1～200 个字符，且只有 `speak` 严格为 `true` 时进入现有 TTS 队列。服务端为异步通知创建新的当前语音句子标识，并取消旧的 LLM 输出，保证现有 TTS 过滤链不会丢弃通知；若用户随后发起更新的对话轮次，则按现有打断语义由新轮次替代该通知。通知不会作为用户输入，也不记录任何二维码或登录凭证。

## 服务端内部接口

Python 播放插件在每次新的播放请求前调用：

```http
POST /config/netease-auth
Authorization: Bearer <server.secret>
Content-Type: application/json

{"macAddress":"11:22:33:44:55:66"}
```

有效登录返回 `authorized`、非敏感账号信息、`credentialVersion` 及仅供服务内部使用的 `internalCredential`。无有效状态时 `authorized=false` 且 `internalCredential=null`。该路径由既有 `server.secret` 过滤器保护；不得暴露到公网、浏览器、日志或 URL。Python 插件会覆盖并忽略旧 agent 参数中的 Cookie；只有 manager-api 成功明确返回 `authorized=false` 时才使用匿名播放。manager-api 未配置、超时、HTTP 失败或数据异常时明确返回服务错误，不会误报登录失效、撤销有效凭证或静默丢失会员权益。刚扫码成功的下一次播放无需重连。

```json
{
  "code": 0,
  "data": {
    "authorized": true,
    "providerUserId": "123456",
    "nickname": "小智用户",
    "avatarUrl": "https://...",
    "internalCredential": "仅服务端内部可见",
    "credentialVersion": 3
  }
}
```

若上游明确返回登录失效，Python 服务调用：

```http
POST /config/netease-auth/invalidate
Authorization: Bearer <server.secret>
Content-Type: application/json

{"macAddress":"11:22:33:44:55:66","credentialVersion":3}
```

```json
{
  "code": 0,
  "data": {
    "invalidated": true
  }
}
```

manager-api 仅在版本仍相同且状态仍为 `LOGGED_IN` 时清除凭证并推进版本；版本不匹配返回 `invalidated=false`，旧播放请求不能撤销之后的新登录。失效回报失败只记录固定告警，不记录 Cookie 或内部凭证。

控制类请求（上一首、下一首、跳转、暂停、继续、停止）只操作当前队列，不重新读取凭证。随机播放会从登录账号的每日推荐或匿名榜单中打乱并建立最多 `max_tracks` 首的队列，而不是只加入一首。用户提出随机播放或播放控制时必须实际调用音乐工具；未收到成功结果前不得口头声称已经播放、切歌或补充队列。缺少歌曲名等请求参数错误直接返回具体补充提示，不得误报为音乐服务不可用。歌单、收藏、每日推荐和私人 FM 等登录专属操作在匿名状态下会明确提示“让当前设备扫码登录”。普通歌曲搜索仍可匿名播放 `fee=0` 或 `fee=8` 的公开免费歌曲。

### 滚动歌词

播放插件在每首歌曲开始前调用网易云 `/lyric` 接口并解析 LRC 时间轴，通过设备 MCP 的 `notifications/netease_music/lyrics` 通知下发。歌词通知与音乐音频共用同一 WebSocket，并严格位于对应歌曲首个音乐帧之前；暂停、停止、打断或队列结束时下发 `clear`。歌词获取失败或歌曲无歌词不会中断音乐，客户端收到 `available=false` 后显示“暂无歌词”。

客户端不得用服务端发包时间估算歌词位置，应从通知后的首个音乐帧开始，以音频输出实际消费的 PCM 样本数计算播放进度，并显示上一行、当前行和下一行。完整字段、状态机、内存上限和验收要求见 `netease-music-lyrics-client-contract.md`。

## 管理接口

超级管理员可强制撤销：

```http
POST /admin/device/{deviceId}/netease/revoke
Authorization: Bearer <admin oauth2 token>
Content-Type: application/json

{"reason":"设备转让"}
```

接口要求 `sys:role:superAdmin`，写入操作日志，并立即终止活动二维码会话、清除本地可用凭证。管理员输入的自由文本不会写入设备授权状态或业务响应；公开原因固定为 `ADMIN_REVOKED`。重复撤销返回 `ALREADY_LOGGED_OUT`。

## 数据模型、恢复与安全边界

- `ai_device_netease_auth`：每个设备一行，保存加密凭证、上游用户信息、状态、撤销原因、时间戳和版本；
- `ai_device_netease_session`：保存一次性会话、加密二维码 key、状态、过期时间和活动槽；`active_slot` 的唯一约束防止同设备并发创建，终态置空；
- 凭证和二维码 key 使用从 `server.secret` 派生的既有 AES 工具封装加密；密钥缺失时明确失败，不会明文回退；
- Cookie 不进入设备响应、状态响应、二维码响应、URL 或日志。只有受 `server.secret` 保护的内部接口会返回字段名明确的 `internalCredential`；
- 授权及二维码会话 DAO 的 MyBatis SQL 日志固定为 `INFO`，防止 DEBUG 参数输出加密凭证、二维码原文或加密 key；
- 服务重启后数据库中的活动会话仍可继续轮询；首次读取时会把超过 `expires_at` 的活动会话终结为 `EXPIRED`。已登录凭证继续有效，无需设备重连；
- 创建登录会话与解绑、按用户删除、按智能体删除设备都会对相同的 `ai_device` 记录执行数据库行锁；删除流程在同一事务内按 MAC 清除授权、终止活动会话，再删除实际选中的设备，避免删除竞态产生孤儿会话或让 MAC 重绑继承旧凭证；
- 上游失败只持久化安全原因（如 `PROVIDER_CREATE_FAILED`、`PROVIDER_POLL_FAILED`、`PROVIDER_LOGOUT_FAILED`），不保存或回显上游敏感正文；
- 音源仍必须属于网易云域名，不调用解灰或替换音源。缓存容量、TTL、单文件 40 MB 限制及播放队列保护沿用原播放插件实现。下载前必须把进行中的下载预留量和新文件大小计入容量，在不删除当前播放队列受保护文件的前提下，按最旧优先淘汰缓存直至为新文件留出空间；不能只在缓存已经超限后清理。随机候选解析失败但最终队列已补足 `max_tracks` 时，仅记录诊断日志，不向用户播报“未能加入播放队列”。

## 配置

管理网页仅配置 Python 播放插件使用的 `api_base_url`、音质、队列长度、准备超时、缓存容量和缓存 TTL；不再显示二维码登录面板或 Cookie 字段。设备端负责展示二维码并按上述协议轮询。

manager-api 生成/轮询二维码使用独立 Spring 配置 `netease-music.api-base-url`，可由环境变量 `NETEASE_MUSIC_API_BASE_URL` 覆盖。源码默认 `http://127.0.0.1:3000` 只适用于 manager-api 与上游运行在同一网络命名空间的本机部署。`docker-compose_all.yml` 不包含网易云上游服务，也不会猜测容器服务名；启用设备登录时必须在启动 web 容器前显式设置 `NETEASE_MUSIC_API_BASE_URL` 为该容器可达地址。它与 Python 插件的 `api_base_url` 必须指向同一个可达的 NeteaseCloudMusicApiEnhanced 实例，但二者处于不同容器时地址写法可能不同。

设备二维码 URL 使用 `netease-music.public-base-url`，由 `NETEASE_MUSIC_PUBLIC_BASE_URL` 提供，必须是设备可访问的 HTTP 或 HTTPS manager-api 基址并包含项目上下文路径。当前局域网部署默认为 `http://192.168.100.149/xiaozhi`；HTTP 仅限可信局域网，跨网或公网部署仍应使用 HTTPS。地址不允许内嵌用户信息、查询参数或 URL 片段。配置不合法时，创建登录会话会明确失败。
