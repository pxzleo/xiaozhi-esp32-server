# Android 通知信息网关 M2 协议

M2 冻结三个手机 REST 接口：`GET /mobile/config`、`POST /mobile/events:batch`、`GET /mobile/events/status`。三者使用 M1 绑定返回的 mobile access token，并要求绑定能力包含 `notification_gateway`；不得使用账号 OAuth 或 `Device-Id`。

请求必须携带 `Authorization: Bearer <mobile token>`、`Mobile-Instance-Id`、`Client-Id=<installation UUID>`、`Mobile-Credential-Version` 和 `Mobile-Protocol-Version: 1`。服务端对实例、安装 UUID、凭据版本、token 哈希、撤销状态和能力做联合校验，失败返回 HTTP 401 `MOBILE_CREDENTIAL_INVALID`。

配置接口返回版本 1 的通知网关可用性、最大摘要 200、最大批次 50 和封闭类别集合，并支持 ETag/304；客户端只有保存了严格解析的完整配置正文时才能使用 304，缓存缺失或非法必须不带 ETag 重取。批次只接受 `notification.state_changed`，每条候选使用严格白名单字段；验证码、token、完整长号码、敏感 channel 及长 URL 在客户端脱敏，服务端再次检测，命中则逐项 `rejected/SENSITIVE_CONTENT`。响应逐项返回 `acknowledged/deduped/rejected/expired`。`event_id` 保证请求幂等，`dedupe_key` 合并同一现实通知状态流。

Android outbox 按 `mobile_instance_id` 隔离，worker 只能发送当前绑定实例；解绑成功删除该实例队列和同步门，禁止新实例代发旧记录。`available=false` 和 429 都写入持久下次执行门；即时唯一 Work 合并连续触发。上传前必须执行服务端配置的 `batch_size/max_summary_length/categories`，超限事件明确拒绝，不得静默截断。

敏感标签后的受控 Unicode/符号跨度必须整体判为敏感，例如中文密码及包含 `@ + / =` 的密码或 token，禁止部分脱敏后残留后缀。

状态接口只返回当前手机实例最近 1–100 条逐次接收审计的 ID、状态、原因码和更新时间，不返回摘要或通知正文。`acknowledged/deduped/rejected/expired` 均持久审计；拒绝记录不保存候选正文。专用异常处理器保留真实 HTTP 状态，且所有接口强制 `Mobile-Protocol-Version: 1`。服务端数据库只保存客户端已脱敏且接受的候选；日志禁止记录正文、token 和签名材料。

缺少 Authorization 返回 HTTP 401；缺少其他手机协议头返回 HTTP 400。同一 `dedupe_key` 的新候选返回 `deduped`，但主事件记录仍按 `occurred_at` 单调更新最新状态、脱敏摘要、证据和有效期；每次接收结果继续单独追加审计。
