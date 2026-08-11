# Android 通知信息网关 M2 协议

M2/M5 冻结三个手机 REST 接口：`GET /mobile/config`、`POST /mobile/events:batch`、`GET /mobile/events/status`。三者使用 M1 绑定返回的 mobile access token；通知事件要求 `notification_gateway`，位置事件要求 `location_gateway`，不得使用账号 OAuth 或 `Device-Id`。

`POST /mobile/events:batch` 的顶层批次和每条事件都必须在 JSON 中显式包含整数 `version=1`。客户端不得依赖语言默认值而省略该字段；服务端对缺失版本按 0 处理并严格拒绝。

请求必须携带 `Authorization: Bearer <mobile token>`、`Mobile-Instance-Id`、`Client-Id=<installation UUID>`、`Mobile-Credential-Version` 和 `Mobile-Protocol-Version: 1`。服务端对实例、安装 UUID、凭据版本、token 哈希、撤销状态和能力做联合校验，失败返回 HTTP 401 `MOBILE_CREDENTIAL_INVALID`。

配置接口始终返回版本 1 的 `notification_gateway={available,max_summary_length,batch_size,categories}`；只有实例能力包含 `location_gateway` 时才额外返回 `location_gateway={available:true}`。旧 M2 绑定响应不得出现该字段或 null，以保持原 JSON 形状兼容。接口支持 ETag/304；客户端只有保存了严格解析的完整配置正文时才能使用 304。批次接受 `notification.state_changed` 和 `location.transition`，每条候选按 type 使用严格白名单字段。

`location.transition` 的 source 固定为 `kind=location/package=android.geofence/channel=null`，state 只允许 `entered/exited/dwelled`，entities 必须且只能为 `place_id/place_name/transition`，evidence 必须且只能为 `rule_id=geofence_transition_v1`。摘要由 transition 与受控地点名称确定，privacy 固定 medium。服务端拒绝经纬度字段、伪造 channel、额外实体和能力不匹配；审计仍只记录 ID、状态、原因与时间，不记录地点名称或坐标。

数据库先由 `202608111200` 创建手机事件表，后续 `202608111400` 独立 drop/re-add `chk_mobile_event_state`，将位置的 `entered/exited/dwelled` 加入原有通知三状态；禁止修改已经发布的 `202608111200`。客户端地点必须绑定创建时的 `mobile_instance_id`，解绑前清除该实例的系统围栏、本地 outbox/冷却与加密地点，清理失败不得由新账号代发。

Android outbox 按 `mobile_instance_id` 隔离，worker 只能发送当前绑定实例；解绑成功删除该实例队列和同步门，禁止新实例代发旧记录。`available=false` 和 429 都写入持久下次执行门；即时唯一 Work 合并连续触发。上传前必须执行服务端配置的 `batch_size/max_summary_length/categories`，超限事件明确拒绝，不得静默截断。

敏感标签后的受控 Unicode/符号跨度必须整体判为敏感，例如中文密码及包含 `@ + / =` 的密码或 token，禁止部分脱敏后残留后缀。

状态接口只返回当前手机实例最近 1–100 条逐次接收审计的 ID、状态、原因码和更新时间，不返回摘要或通知正文。`acknowledged/deduped/rejected/expired` 均持久审计；拒绝记录不保存候选正文。专用异常处理器保留真实 HTTP 状态，且所有接口强制 `Mobile-Protocol-Version: 1`。服务端数据库只保存客户端已脱敏且接受的候选；日志禁止记录正文、token 和签名材料。

缺少 Authorization 返回 HTTP 401；缺少其他手机协议头返回 HTTP 400。同一 `dedupe_key` 的新候选返回 `deduped`，但主事件记录仍按 `occurred_at` 单调更新最新状态、脱敏摘要、证据和有效期；每次接收结果继续单独追加审计。
