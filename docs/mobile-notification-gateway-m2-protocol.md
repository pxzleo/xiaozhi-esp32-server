# Android 通知信息网关 M2 协议

M2/M5 冻结三个手机 REST 接口：`GET /mobile/config`、`POST /mobile/events:batch`、`GET /mobile/events/status`。三者使用 M1 绑定返回的 mobile access token；通知事件要求 `notification_gateway`，位置事件要求 `location_gateway`，不得使用账号 OAuth 或 `Device-Id`。

`POST /mobile/events:batch` 的顶层批次和每条事件都必须在 JSON 中显式包含整数 `version=1`。客户端不得依赖语言默认值而省略该字段；服务端对缺失版本按 0 处理并严格拒绝。

请求必须携带 `Authorization: Bearer <mobile token>`、`Mobile-Instance-Id`、`Client-Id=<installation UUID>`、`Mobile-Credential-Version` 和 `Mobile-Protocol-Version: 1`。服务端对实例、安装 UUID、凭据版本、token 哈希、撤销状态和能力做联合校验，失败返回 HTTP 401 `MOBILE_CREDENTIAL_INVALID`。

配置接口始终返回版本 1 的 `notification_gateway={available,max_summary_length,batch_size,categories}`；只有实例能力包含 `location_gateway` 时才额外返回 `location_gateway={available:true}`。旧 M2 绑定响应不得出现该字段或 null，以保持原 JSON 形状兼容。接口支持 ETag/304；客户端只有保存了严格解析的完整配置正文时才能使用 304。批次接受 `notification.state_changed` 和 `location.transition`，每条候选按 type 使用严格白名单字段。

`location.transition` 的 source 固定为 `kind=location/package=android.geofence/channel=null`，state 只允许 `entered/exited/dwelled`，entities 必须且只能为 `place_id/place_name/transition`，evidence 必须且只能为 `rule_id=geofence_transition_v1`。摘要由 transition 与受控地点名称确定，privacy 固定 medium。服务端拒绝经纬度字段、伪造 channel、额外实体和能力不匹配；审计仍只记录 ID、状态、原因与时间，不记录地点名称或坐标。

数据库先由 `202608111200` 创建手机事件表，后续 `202608111400` 独立 drop/re-add `chk_mobile_event_state`，将位置的 `entered/exited/dwelled` 加入原有通知三状态；禁止修改已经发布的 `202608111200`。客户端地点必须绑定创建时的 `mobile_instance_id`，解绑前清除该实例的系统围栏、本地 outbox/冷却与加密地点，清理失败不得由新账号代发。

Android outbox 按 `mobile_instance_id` 隔离，worker 只能发送当前绑定实例；解绑成功删除该实例队列和同步门，禁止新实例代发旧记录。`available=false` 和 429 都写入持久下次执行门；即时唯一 Work 合并连续触发。上传前必须执行服务端配置的 `batch_size/max_summary_length/categories`，超限事件明确拒绝，不得静默截断。

敏感标签后的受控 Unicode/符号跨度必须整体判为敏感，例如中文密码及包含 `@ + / =` 的密码或 token，禁止部分脱敏后残留后缀。

通知二次隐私检查按字段职责执行：`summary/sender_hint/thread_hint` 使用完整敏感内容规则；`source.channel` 只检查敏感标签和 URL，允许合法纯数字系统频道；`evidence.rule_id/transition/notification_key_hash` 先按封闭枚举或格式验证，其中通知 key 哈希只允许 64 位小写十六进制。合法哈希或数字频道不得按长号码误拒，畸形元数据返回 `INVALID_EVENT_SHAPE`。

状态接口只返回当前手机实例最近 1–100 条逐次接收审计的 ID、状态、原因码和更新时间，不返回摘要或通知正文。`acknowledged/deduped/rejected/expired` 均持久审计；拒绝记录不保存候选正文。专用异常处理器保留真实 HTTP 状态，且所有接口强制 `Mobile-Protocol-Version: 1`。服务端数据库只保存客户端已脱敏且接受的候选；日志禁止记录正文、token 和签名材料。

缺少 Authorization 返回 HTTP 401；缺少其他手机协议头返回 HTTP 400。同一 `dedupe_key` 的新候选返回 `deduped`，但主事件记录仍按 `occurred_at` 单调更新最新状态、脱敏摘要、证据和有效期；每次接收结果继续单独追加审计。

## 账号侧处理审计与提醒转换

`GET /mobile/events/audit` 是账号 OAuth 的 `sys:role:normal` 接口，不使用手机凭据，且不属于匿名手机协议路由。请求必须提供 `mobile_instance_id`；服务端先按当前 `user_id` 校验手机实例所有权，再执行分页查询。可选筛选为 `type`、`processing_status`、`delivery_status`、ISO-8601 `from/to`，分页 `page=1..1000`、`limit=1..100`。响应只包含实例/设备/事件 ID、类型、来源包、状态、已脱敏摘要、类别、级别、置信度、受控播报摘要、受控原因码、处理阶段、时间、关联主动事件 ID 及其真实投递状态；不得返回 mobile token、证据 JSON、租约、坐标、原始正文或模型自由推理。原 `GET /mobile/events/status` 的手机凭据兼容接口保持不变。

后续迁移 `202608111600` 扩展 `ai_mobile_event`，处理阶段为 `received/prefiltered/classified/ignored/converted/error`，并保存受控分类结果、关联主动事件 ID、120 秒数据库租约、尝试次数、下次重试和处理时间。领取、到期和租约接管均使用数据库 `CURRENT_TIMESTAMP(3)`；失败按指数退避。`removed`、已过期事件和已终结状态的重复更新不进入分类或播报。

通知先执行确定性预筛，低价值候选以 `processing_status=prefiltered/reason_code=prefilter_low_value` 结束且不调用模型；只有 `security/call/parcel/appointment/message/other` 中命中严格规则的少量候选才调用全局独立主动分类模型。输入只有已脱敏 `summary/category/source_package/state`；外部文本是低信任数据。模型必须返回且只返回 `should_notify/category/severity/confidence/spoken_summary/reason_code` 单一 JSON 根对象；模型未配置、不可用、调用失败或 JSON 不严格时写 `error` 并退避，禁止回退设备智能体模型。仅 `high/critical` 且 `confidence>=0.85` 转换为提醒。

用户在 M5 显式保存并启用的 enter/exit/dwell 是位置主动提醒授权。`location.transition` 不调用 LLM；通过实例、严格形状和有效期校验后，服务端仅用受控地点名重构“已进入/已离开/已驻留 + 地点名”，按 `place_id+transition` 计算 24 小时滚动去重并生成普通优先级提醒。经纬度和客户端自由文本不得进入 payload 或模型。

同一通知生命周期的更晚 `updated/removed` 在更新手机事件的事务中失效旧 `delivery_group` 的未实际投递副本：PENDING 写入 dismissed 真实终态；CLAIMED 保留领取事实但把有效期推进到数据库当前时间，使后续权威读取和 complete CAS 拒绝；DELIVERED 不改写。`updated` 若再次达到提醒门槛，按 `dedupe_key + occurred_at revision` 生成新内部去重键，以新的投递组和最新受控 payload 创建提醒，不能命中旧 24 小时窗口复用旧内容。手机和音箱在实际播报前必须重新读取权威事件；Android 对 `MOBILE_ALERT` 使用同一 token 再次 claim，同 token 幂等且不得刷新 180 秒租约，复验冲突时停止呈现。

账号审计的 `delivery_status` 在 SQL 查询时按数据库当前时间派生，且筛选必须使用相同表达式：`expires_at<=CURRENT_TIMESTAMP(3)` 显示 `expired`；底层为 CLAIMED 但领取时间为空或早于当前时间 180 秒的记录显示 `pending`，与可重领语义一致；其余状态保留底层真实值。这样不会把已过期或租约失效的提醒继续展示为正在领取，也不覆盖真实投递历史。
