package xiaozhi.modules.device.proactive;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventStatusUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventCreateResult;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventClaim;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitObserve;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceView;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.DedupePolicy;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.HabitType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

@Service
public class ProactiveService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProactiveService.class);
    private static final Set<String> EVENT_PAYLOAD_KEYS = Set.of(
            "title", "message", "summary", "category", "reference_id", "reference_url",
            "scheduled_at", "action", "source");
    private static final Set<String> HABIT_PAYLOAD_KEYS = Set.of(
            "description", "suggested_mode", "suggested_time", "topic");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DeviceDao deviceDao;
    private final ProactivePreferenceDao preferenceDao;
    private final ProactiveEventDao eventDao;
    private final ProactiveEventDedupeDao eventDedupeDao;
    private final ProactiveDeliveryClaimDao deliveryClaimDao;
    private final ProactiveGlobalDao globalDao;
    private final ProactiveHabitDao habitDao;
    private final ObjectMapper objectMapper;

    public ProactiveService(DeviceDao deviceDao, ProactivePreferenceDao preferenceDao,
            ProactiveEventDao eventDao, ProactiveEventDedupeDao eventDedupeDao,
            ProactiveDeliveryClaimDao deliveryClaimDao,
            ProactiveGlobalDao globalDao, ProactiveHabitDao habitDao, ObjectMapper objectMapper) {
        this.deviceDao = deviceDao;
        this.preferenceDao = preferenceDao;
        this.eventDao = eventDao;
        this.eventDedupeDao = eventDedupeDao;
        this.deliveryClaimDao = deliveryClaimDao;
        this.globalDao = globalDao;
        this.habitDao = habitDao;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PreferenceView getPreferenceByMac(String macAddress) {
        return preference(resolveByMac(macAddress));
    }

    @Transactional
    public PreferenceView updatePreferenceByMac(String macAddress, PreferenceUpdate request) {
        return updatePreference(resolveByMac(macAddress), request);
    }

    @Transactional
    public List<PreferenceView> listPreferences(Long userId) {
        return devicesForUser(userId).stream().map(this::preference).toList();
    }

    @Transactional
    public PreferenceView getPreference(Long userId, String deviceId) {
        return preference(requireOwned(userId, deviceId));
    }

    @Transactional
    public PreferenceView updatePreference(Long userId, String deviceId, PreferenceUpdate request) {
        return updatePreference(requireOwned(userId, deviceId), request);
    }

    @Transactional
    public PreferenceView silentToday(Long userId, String deviceId) {
        DeviceEntity device = requireOwned(userId, deviceId);
        ProactivePreferenceEntity entity = preferenceEntity(device);
        Date now = new Date();
        if (!Mode.TODAY_SILENT.name().equals(entity.getMode())) {
            entity.setPreviousMode(entity.getMode());
            entity.setPreviousDailyLimit(entity.getDailyLimit());
        }
        entity.setMode(Mode.TODAY_SILENT.name());
        entity.setDailyLimit(0);
        entity.setSilentUntil(Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(now);
        if (preferenceDao.updateById(entity) != 1) throw new RenException("主动助理偏好更新失败");
        return toPreference(entity);
    }

    @Transactional
    public EventView upsertEvent(EventUpsert request) {
        if (request.getEventType() == EventType.MOBILE_ALERT) {
            throw new RenException("手机感知事件只能使用内部受控入口");
        }
        if (isExternal(request.getEventType())) {
            throw new RenException("外界监测事件必须使用monitor-events接口");
        }
        return createEvent(request).event();
    }

    @Transactional
    public EventCreateResult createMonitorEvent(EventUpsert request) {
        if (!isExternal(request.getEventType())) {
            throw new RenException("monitor-events接口仅允许外界监测事件");
        }
        return createEvent(request);
    }

    @Transactional
    public EventCreateResult createMobileAlert(String mobileInstanceId, Long userId, String agentId,
            String sourceEventId,
            String dedupeKey, String title, String summary, String source, String category,
            Priority priority, Date createdAt, Date expiresAt) {
        if (StringUtils.isAnyBlank(mobileInstanceId, sourceEventId, dedupeKey, title, summary,
                source, category, agentId) || userId == null
                || !mobileInstanceId.matches("^mob_[0-9a-f]{32}$")
                || !sourceEventId.matches("^[A-Za-z0-9._:-]{1,64}$")
                || !dedupeKey.matches("^sha256:[0-9a-f]{64}$")
                || !Set.of("security", "call", "parcel", "appointment", "message", "other", "location")
                        .contains(category)
                || priority == null || createdAt == null || expiresAt == null
                || !expiresAt.after(createdAt)) {
            throw new RenException("手机主动事件内部参数无效");
        }
        EventUpsert request = new EventUpsert();
        request.setEventId(sourceEventId);
        request.setTopic(Topic.SYSTEM);
        request.setPriority(priority);
        request.setReason("mobile_event_classified");
        request.setEventType(EventType.MOBILE_ALERT);
        request.setPayload(Map.of("title", title, "summary", summary,
                "source", source, "category", category));
        request.setCreatedAt(createdAt);
        request.setExpiresAt(expiresAt);
        request.setDedupeKey(dedupeKey);
        request.setDedupePolicy(DedupePolicy.ROLLING_WINDOW);
        request.setDedupeWindowHours(24);
        request.setRequiresResponse(false);
        if (!request.isMonitorTopicValid() || !request.isDedupePolicyValid()) {
            throw new RenException("手机主动事件内部契约无效");
        }
        validateEventPayload(request.getPayload());
        List<DeviceEntity> targets = deviceDao.selectByAgentIdForUpdate(agentId).stream()
                .filter(device -> userId.equals(device.getUserId()))
                .toList();
        if (targets.isEmpty() || targets.stream()
                .noneMatch(device -> mobileInstanceId.equals(device.getMacAddress()))) {
            throw new RenException("手机主动事件目标设备不存在");
        }
        EventCreateResult mobileResult = null;
        for (DeviceEntity target : targets) {
            request.setMacAddress(target.getMacAddress());
            EventCreateResult result = createRollingWindowEvent(target, request);
            if (mobileInstanceId.equals(target.getMacAddress())) mobileResult = result;
        }
        if (mobileResult == null) throw new RenException("手机主动事件目标设备不存在");
        return mobileResult;
    }

    private EventCreateResult createEvent(EventUpsert request) {
        DeviceEntity device = resolveByMac(request.getMacAddress());
        if (!request.isMonitorTopicValid()) throw new RenException("外界监测事件的topic与event_type不匹配");
        if (!request.isDedupePolicyValid()) throw new RenException("外界监测事件去重策略无效");
        validateEventPayload(request.getPayload());
        if (deviceDao.selectByIdForUpdate(device.getId()) == null) throw new RenException("设备不存在");
        boolean external = isExternal(request.getEventType());
        if (external) {
            String globalValue = globalDao.selectExternalMonitoringValueForUpdate();
            if (StringUtils.isBlank(globalValue) || "false".equalsIgnoreCase(globalValue.trim())) {
                throw new RenException("外界监测全局开关未开启");
            }
            if (!"true".equalsIgnoreCase(globalValue.trim())) {
                throw new RenException("外界监测全局开关系统参数无效");
            }
        }
        if (external && (StringUtils.isBlank(request.getDedupeKey())
                || !request.getDedupeKey().matches("[A-Za-z0-9:_-]{1,128}"))) {
            throw new RenException("外界监测事件dedupe_key格式无效");
        }
        if (request.getDedupePolicy() == DedupePolicy.ROLLING_WINDOW) {
            return createRollingWindowEvent(device, request);
        }
        EventWrite write = upsertEventLocked(device, request, request.getEventId());
        return new EventCreateResult(write.created(), !write.created(),
                write.event().eventId(), write.event(), write.event().createdAt());
    }

    private boolean isExternal(EventType eventType) {
        return eventType == EventType.WEATHER_ALERT || eventType == EventType.NEWS_ALERT;
    }

    private EventCreateResult createRollingWindowEvent(DeviceEntity device, EventUpsert request) {
        String eventType = request.getEventType().name();
        String dedupeHash = sha256(request.getDedupeKey());
        eventDedupeDao.insertIfAbsent(device.getId(), eventType, dedupeHash);
        ProactiveEventDedupeEntity ledger = eventDedupeDao.selectForUpdate(
                device.getId(), eventType, dedupeHash);
        if (ledger == null) {
            throw new RenException("外界监测事件去重账本创建失败");
        }
        String recentEventId = eventDedupeDao.selectRecentEventId(device.getId(), eventType,
                dedupeHash, request.getDedupeWindowHours());
        if (StringUtils.isNotBlank(recentEventId)) {
            ProactiveEventEntity authoritative = eventDao.selectByDeviceAndEventId(
                    device.getId(), recentEventId);
            if (authoritative == null) throw new RenException("外界监测事件去重账本引用无效");
            Date dedupeRecordedAt = eventDedupeDao.selectLastCreatedAt(
                    device.getId(), eventType, dedupeHash);
            if (dedupeRecordedAt == null) {
                throw new RenException("外界监测事件去重账本时间无效");
            }
            return new EventCreateResult(false, true, recentEventId,
                    toEvent(authoritative), dedupeRecordedAt);
        }
        EventWrite write = upsertEventLocked(device, request, "ext-" + UUID.randomUUID());
        if (eventDedupeDao.markCreated(device.getId(), eventType, dedupeHash,
                write.event().eventId()) != 1) {
            throw new RenException("外界监测事件去重账本更新失败");
        }
        Date dedupeRecordedAt = eventDedupeDao.selectLastCreatedAt(
                device.getId(), eventType, dedupeHash);
        if (dedupeRecordedAt == null) {
            throw new RenException("外界监测事件去重账本时间无效");
        }
        return new EventCreateResult(write.created(), !write.created(),
                write.event().eventId(), write.event(), dedupeRecordedAt);
    }

    private EventWrite upsertEventLocked(DeviceEntity device, EventUpsert request, String eventId) {
        ProactiveEventEntity existing = eventDao.selectByDeviceAndEventIdForUpdate(
                device.getId(), eventId);
        if (existing != null) {
            verifyIdempotentEvent(existing, request);
            return new EventWrite(false, toEvent(existing));
        }
        Date now = new Date();
        ProactiveEventEntity entity = new ProactiveEventEntity();
        entity.setDeviceId(device.getId());
        entity.setMacAddress(device.getMacAddress());
        entity.setEventId(eventId);
        entity.setTopic(request.getTopic().name());
        entity.setPriority(request.getPriority().name());
        entity.setReason(request.getReason());
        entity.setEventType(request.getEventType().name());
        entity.setPayload(writeJson(request.getPayload()));
        entity.setCreatedAt(request.getCreatedAt());
        entity.setExpiresAt(request.getExpiresAt());
        entity.setDedupeKey(request.getDedupeKey());
        boolean rollingGroup = request.getDedupePolicy() == DedupePolicy.ROLLING_WINDOW;
        // 外界事件可能按设备生成不同 event_id；跨前端竞争键必须来自跨设备稳定的权威去重身份。
        String groupIdentity = isExternal(request.getEventType())
                || request.getEventType() == EventType.MOBILE_ALERT
                ? request.getDedupeKey() : request.getEventId();
        entity.setDeliveryGroupKey(sha256(request.getEventType().name() + ":" + groupIdentity));
        entity.setDeliveryGroupWindowHours(rollingGroup ? request.getDedupeWindowHours() : 0);
        entity.setRequiresResponse(request.getRequiresResponse());
        entity.setDeliveryStatus(DeliveryStatus.PENDING.name());
        entity.setOutcome(Outcome.NONE.name());
        entity.setUpdatedAt(now);
        int inserted = eventDao.insertIfAbsent(entity);
        ProactiveEventEntity stored = eventDao.selectByDeviceAndEventIdForUpdate(
                device.getId(), eventId);
        if (stored == null) throw new RenException("主动事件写入失败");
        // MySQL DATETIME is timezone-less while the JDBC session is UTC. Re-reading a freshly
        // inserted row can therefore shift Date by the host offset. The insert result is the
        // authority for new-vs-existing; only an existing event needs full idempotency comparison.
        if (inserted == 0) verifyIdempotentEvent(stored, request);
        return new EventWrite(inserted == 1, toEvent(stored));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM不支持SHA-256", exception);
        }
    }

    private record EventWrite(boolean created, EventView event) {}

    @Transactional
    public EventView updateEventStatus(String eventId, EventStatusUpdate request) {
        DeviceEntity device = resolveByMac(request.getMacAddress());
        ProactiveEventEntity current = eventDao.selectByDeviceAndEventIdForUpdate(
                device.getId(), eventId);
        if (current == null) {
            throw new RenException("主动事件不存在");
        }
        DeliveryStatus source = DeliveryStatus.valueOf(current.getDeliveryStatus());
        DeliveryStatus target = request.getDeliveryStatus();
        String claimToken = request.getClaimToken();
        boolean transitionAllowed = switch (source) {
            case PENDING -> target == DeliveryStatus.DELIVERED || target == DeliveryStatus.FAILED
                    || target == DeliveryStatus.DISMISSED;
            case CLAIMED -> (target == DeliveryStatus.DELIVERED || target == DeliveryStatus.FAILED)
                    && claimToken != null && claimToken.equals(current.getClaimToken());
            case DELIVERED -> target == DeliveryStatus.DELIVERED
                    && (current.getClaimToken() == null
                        || current.getClaimToken().equals(claimToken));
            default -> false;
        };
        if (!transitionAllowed || target == DeliveryStatus.CLAIMED
                || target == DeliveryStatus.DISMISSED && request.getOutcome() != Outcome.DISMISSED) {
            throw new RenException("主动事件状态转换无效");
        }
        if (eventDao.updateStatusCas(device.getId(), eventId, source.name(), claimToken,
                target.name(), request.getOutcome().name(), new Date()) != 1) {
            throw new RenException("主动事件状态已变化");
        }
        if (source == DeliveryStatus.CLAIMED && StringUtils.isNotBlank(current.getDeliveryGroupKey())) {
            String groupStatus = target == DeliveryStatus.DELIVERED ? "DELIVERED" : "FAILED";
            if (deliveryClaimDao.complete(device.getUserId(), current.getDeliveryGroupKey(),
                    claimToken, groupStatus, new Date()) != 1) {
                throw new RenException("主动事件跨前端终态更新失败");
            }
        }
        ProactiveEventEntity event = eventDao.selectByDeviceAndEventId(device.getId(), eventId);
        return toEvent(event);
    }

    @Transactional
    public boolean claimEvent(String eventId, EventClaim request) {
        DeviceEntity device = resolveByMac(request.getMacAddress());
        if (eventDao.claimPending(device.getId(), eventId, request.getClaimToken()) != 1) {
            return false;
        }
        ProactiveEventEntity event = eventDao.selectByDeviceAndEventId(device.getId(), eventId);
        boolean eventClaimed = event != null
                && DeliveryStatus.CLAIMED.name().equals(event.getDeliveryStatus())
                && request.getClaimToken().equals(event.getClaimToken());
        if (!eventClaimed || StringUtils.isBlank(event.getDeliveryGroupKey())) return false;
        deliveryClaimDao.insertIfAbsent(device.getUserId(), event.getDeliveryGroupKey());
        if (deliveryClaimDao.claim(device.getUserId(), event.getDeliveryGroupKey(), device.getId(),
                eventId, request.getClaimToken(), event.getCreatedAt(),
                event.getDeliveryGroupWindowHours() == null ? 0 : event.getDeliveryGroupWindowHours()) != 1) {
            if (eventDao.releaseClaim(device.getId(), eventId, request.getClaimToken()) != 1) {
                throw new RenException("主动事件跨前端领取冲突回滚失败");
            }
            return false;
        }
        return true;
    }

    @Transactional
    public HabitView observeHabit(HabitObserve request) {
        DeviceEntity device = resolveByMac(request.getMacAddress());
        validateHabitPayload(request.getPayload());
        ProactiveHabitEntity entity = new ProactiveHabitEntity();
        entity.setDeviceId(device.getId());
        entity.setMacAddress(device.getMacAddress());
        entity.setHabitType(request.getHabitType().name());
        entity.setHabitKey(request.getHabitKey());
        entity.setEvidenceCount(request.getEvidenceDelta());
        entity.setFirstSeenAt(request.getSeenAt());
        entity.setLastSeenAt(request.getSeenAt());
        entity.setPayload(writeJson(request.getPayload()));
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        habitDao.observeAtomic(entity);
        ProactiveHabitEntity stored = habitDao.selectOne(new LambdaQueryWrapper<ProactiveHabitEntity>()
                .eq(ProactiveHabitEntity::getDeviceId, device.getId())
                .eq(ProactiveHabitEntity::getHabitType, request.getHabitType().name())
                .eq(ProactiveHabitEntity::getHabitKey, request.getHabitKey()));
        return toHabit(stored);
    }

    public List<HabitView> candidatesByMac(String macAddress) {
        DeviceEntity device = resolveByMac(macAddress);
        return habitDao.selectList(new LambdaQueryWrapper<ProactiveHabitEntity>()
                .eq(ProactiveHabitEntity::getDeviceId, device.getId())
                .eq(ProactiveHabitEntity::getSuggested, true)
                .eq(ProactiveHabitEntity::getAccepted, false)
                .eq(ProactiveHabitEntity::getDismissed, false)
                .orderByDesc(ProactiveHabitEntity::getLastSeenAt)).stream().map(this::toHabit).toList();
    }

    public EventView monitorEvent(String macAddress, String eventId) {
        if (StringUtils.isAnyBlank(macAddress, eventId)) throw new RenException("外界事件查询参数不能为空");
        ProactiveEventEntity event = eventDao.selectMonitorEventByMacAndEventId(macAddress, eventId);
        if (event == null) throw new RenException("外界监测事件不存在");
        return toEvent(event);
    }

    public EventView claimedMonitorEvent(String macAddress, String eventId, String claimToken) {
        if (StringUtils.isAnyBlank(macAddress, eventId, claimToken)) {
            throw new RenException("已领取外界事件查询参数不能为空");
        }
        ProactiveEventEntity event = eventDao.selectClaimedMonitorEvent(
                macAddress, eventId, claimToken, new Date());
        if (event == null) throw new RenException("已领取外界监测事件不存在或已失效");
        return toEvent(event);
    }

    public PageData<EventView> events(Long userId, String deviceId, Topic topic,
            DeliveryStatus status, EventType eventType, int page, int limit) {
        if (deviceId != null) requireOwned(userId, deviceId);
        int safePage = requireRange(page, 1, 1_000, "page");
        int safeLimit = requireRange(limit, 1, 100, "limit");
        String topicName = topic == null ? null : topic.name();
        String statusName = status == null ? null : status.name();
        String typeName = eventType == null ? null : eventType.name();
        List<EventView> list = eventDao.pageForUser(userId, deviceId, topicName, statusName, typeName,
                safeLimit, ((long) safePage - 1L) * safeLimit).stream().map(this::toEvent).toList();
        long total = eventDao.countForUser(userId, deviceId, topicName, statusName, typeName);
        return new PageData<>(list, total);
    }

    public List<HabitView> habits(Long userId, String deviceId) {
        List<String> deviceIds;
        if (deviceId != null) {
            requireOwned(userId, deviceId);
            deviceIds = List.of(deviceId);
        } else {
            deviceIds = devicesForUser(userId).stream().map(DeviceEntity::getId).toList();
        }
        if (deviceIds.isEmpty()) return List.of();
        return habitDao.selectList(new LambdaQueryWrapper<ProactiveHabitEntity>()
                .in(ProactiveHabitEntity::getDeviceId, deviceIds)
                .orderByDesc(ProactiveHabitEntity::getLastSeenAt)).stream().map(this::toHabit).toList();
    }

    @Transactional
    public void deleteHabit(Long userId, Long habitId) {
        ProactiveHabitEntity habit = habitDao.selectById(habitId);
        if (habit == null) throw new RenException("习惯候选不存在");
        requireOwned(userId, habit.getDeviceId());
        if (habitDao.deleteById(habitId) != 1) throw new RenException("习惯候选删除失败");
    }

    private PreferenceView updatePreference(DeviceEntity device, PreferenceUpdate request) {
        ProactivePreferenceEntity entity = preferenceEntity(device);
        Mode mode = request.getMode();
        String priorMode = entity.getMode();
        Integer priorDailyLimit = entity.getDailyLimit();
        int limit = request.getDailyLimit() == null ? defaultLimit(mode) : request.getDailyLimit();
        entity.setMode(mode.name());
        entity.setDailyLimit(limit);
        entity.setQuietStart(request.getQuietStart());
        entity.setQuietEnd(request.getQuietEnd());
        entity.setAllowedTopics(writeJson(request.getAllowedTopics() == null ? Set.of() : request.getAllowedTopics()));
        entity.setBlockedTopics(writeJson(request.getBlockedTopics() == null ? Set.of() : request.getBlockedTopics()));
        if (mode == Mode.TODAY_SILENT) {
            if (StringUtils.isBlank(entity.getPreviousMode())) {
                entity.setPreviousMode(priorMode);
                entity.setPreviousDailyLimit(priorDailyLimit);
            }
            entity.setSilentUntil(Date.from(LocalDate.now().plusDays(1)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant()));
        } else {
            entity.setPreviousMode(null);
            entity.setPreviousDailyLimit(null);
            entity.setSilentUntil(null);
        }
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(new Date());
        if (preferenceDao.updateById(entity) != 1) throw new RenException("主动助理偏好更新失败");
        return toPreference(entity);
    }

    private PreferenceView preference(DeviceEntity device) {
        return toPreference(preferenceEntity(device));
    }

    private ProactivePreferenceEntity preferenceEntity(DeviceEntity device) {
        Date now = new Date();
        preferenceDao.insertDefault(device.getId(), requireMac(device), now);
        preferenceDao.restoreExpiredSilent(device.getId(), now);
        ProactivePreferenceEntity entity = preferenceDao.selectById(device.getId());
        if (entity == null) throw new RenException("主动助理偏好读取失败");
        return normalizeLegacyAggressiveLimit(entity, now);
    }

    private ProactivePreferenceEntity normalizeLegacyAggressiveLimit(
            ProactivePreferenceEntity entity, Date now) {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean currentLegacy = Mode.AGGRESSIVE.name().equals(entity.getMode()) &&
                    isLegacyAggressiveLimit(entity.getDailyLimit());
            if (!hasLegacyAggressiveLimit(entity)) return entity;
            if (currentLegacy) {
                preferenceDao.normalizeLegacyAggressiveLimit(entity.getDeviceId(),
                        entity.getDailyLimit(), entity.getVersion(), now);
            } else {
                preferenceDao.normalizeLegacyPreviousAggressiveLimit(entity.getDeviceId(),
                        entity.getPreviousDailyLimit(), entity.getVersion(), now);
            }
            entity = preferenceDao.selectByIdForUpdate(entity.getDeviceId());
            if (entity == null) throw new RenException("主动助理偏好读取失败");
            if (!hasLegacyAggressiveLimit(entity)) return entity;
        }
        throw new RenException("旧版积极模式偏好规范化并发冲突");
    }

    private boolean hasLegacyAggressiveLimit(ProactivePreferenceEntity entity) {
        return (Mode.AGGRESSIVE.name().equals(entity.getMode()) &&
                isLegacyAggressiveLimit(entity.getDailyLimit())) ||
                (Mode.TODAY_SILENT.name().equals(entity.getMode()) &&
                Mode.AGGRESSIVE.name().equals(entity.getPreviousMode()) &&
                isLegacyAggressiveLimit(entity.getPreviousDailyLimit()));
    }

    private boolean isLegacyAggressiveLimit(Integer limit) {
        return limit != null && limit >= 1 && limit <= 5;
    }

    private DeviceEntity resolveByMac(String macAddress) {
        if (StringUtils.isBlank(macAddress)) throw new RenException("mac_address不能为空");
        List<DeviceEntity> devices = deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getMacAddress, macAddress).last("LIMIT 2"));
        if (devices.isEmpty()) throw new RenException("设备不存在");
        if (devices.size() > 1) throw new RenException("MAC对应多设备");
        return devices.getFirst();
    }

    private DeviceEntity requireOwned(Long userId, String deviceId) {
        if (userId == null || StringUtils.isBlank(deviceId)) throw new RenException("设备不存在");
        DeviceEntity device = deviceDao.selectById(deviceId);
        if (device == null || !userId.equals(device.getUserId())) throw new RenException("设备不存在");
        return device;
    }

    private List<DeviceEntity> devicesForUser(Long userId) {
        if (userId == null) throw new RenException("用户未登录");
        return deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getUserId, userId).orderByAsc(DeviceEntity::getSort));
    }

    private String requireMac(DeviceEntity device) {
        if (StringUtils.isBlank(device.getMacAddress())) throw new RenException("设备MAC地址不存在");
        return device.getMacAddress();
    }

    private int defaultLimit(Mode mode) {
        return switch (mode) {
            case CONSERVATIVE -> 1;
            case ACTIVE -> 5;
            case AGGRESSIVE -> 0;
            case TODAY_SILENT -> 0;
        };
    }

    private void validatePayloadKeysAndSize(Map<String, Object> payload, Set<String> allowedKeys, String name) {
        if (payload == null || !allowedKeys.containsAll(payload.keySet())) {
            throw new RenException(name + "包含不支持的键");
        }
        if (writeJson(payload).getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new RenException(name + "总长度不能超过512字节");
        }
    }

    private void validateEventPayload(Map<String, Object> payload) {
        if (payload == null || !EVENT_PAYLOAD_KEYS.containsAll(payload.keySet())) {
            throw new RenException("event payload包含不支持的键");
        }
        Map<String, Object> boundedPayload = new HashMap<>(payload);
        boundedPayload.remove("reference_url");
        if (writeJson(boundedPayload).getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new RenException("event payload除reference_url外总长度不能超过512字节");
        }
        validateOptionalText(payload, "title", 100, "event payload title");
        validateOptionalText(payload, "message", 300, "event payload message");
        validateOptionalText(payload, "reference_id", 128, "event payload reference_id");
        validateReferenceUrl(payload);
        validateOptionalText(payload, "action", 64, "event payload action");
        validateOptionalText(payload, "source", 64, "event payload source");
        if (payload.containsKey("scheduled_at")) {
            String value = requireText(payload.get("scheduled_at"), 40, "event payload scheduled_at");
            try {
                LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException exception) {
                throw new RenException("event payload scheduled_at必须为ISO本地日期时间", exception);
            }
        }
    }

    private void validateReferenceUrl(Map<String, Object> payload) {
        if (!payload.containsKey("reference_url")) return;
        String value = requireText(payload.get("reference_url"), 2048, "event payload reference_url");
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || StringUtils.isBlank(uri.getHost()) || uri.getRawUserInfo() != null) {
                throw new RenException("event payload reference_url必须为不含用户信息的HTTP(S) URL");
            }
        } catch (URISyntaxException exception) {
            throw new RenException("event payload reference_url必须为合法HTTP(S) URL", exception);
        }
    }

    private void validateHabitPayload(Map<String, Object> payload) {
        validatePayloadKeysAndSize(payload, HABIT_PAYLOAD_KEYS, "habit payload");
        validateOptionalText(payload, "description", 255, "habit payload description");
        if (payload.containsKey("topic")) {
            Topic.fromWire(requireText(payload.get("topic"), 32, "habit payload topic"));
        }
        if (payload.containsKey("suggested_mode")) {
            Mode mode = Mode.fromWire(requireText(payload.get("suggested_mode"), 24,
                    "habit payload suggested_mode"));
            if (mode == Mode.TODAY_SILENT) {
                throw new RenException("habit payload suggested_mode不允许today_silent");
            }
        }
        if (payload.containsKey("suggested_time")) {
            String value = requireText(payload.get("suggested_time"), 5, "habit payload suggested_time");
            if (!value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
                throw new RenException("habit payload suggested_time必须为HH:mm");
            }
        }
    }

    private void validateOptionalText(Map<String, Object> payload, String key, int maxLength, String name) {
        if (payload.containsKey(key)) requireText(payload.get(key), maxLength, name);
    }

    private String requireText(Object value, int maxLength, String name) {
        if (!(value instanceof String text) || StringUtils.isBlank(text)) {
            throw new RenException(name + "必须为非空字符串");
        }
        if (text.length() > maxLength) throw new RenException(name + "长度超限");
        return text;
    }

    private int requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw new RenException(name + "超出允许范围");
        return value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new RenException("JSON序列化失败", exception);
        }
    }

    private void verifyIdempotentEvent(ProactiveEventEntity stored, EventUpsert request) {
        List<String> mismatches = new ArrayList<>();
        if (!Objects.equals(stored.getTopic(), request.getTopic().name())) mismatches.add("topic");
        if (!Objects.equals(stored.getPriority(), request.getPriority().name())) mismatches.add("priority");
        if (!Objects.equals(stored.getReason(), request.getReason())) mismatches.add("reason");
        if (!Objects.equals(stored.getEventType(), request.getEventType().name())) mismatches.add("event_type");
        if (!Objects.equals(readMap(stored.getPayload()), request.getPayload())) mismatches.add("payload");
        if (!sameSecond(stored.getCreatedAt(), request.getCreatedAt())) mismatches.add("created_at");
        if (!sameSecond(stored.getExpiresAt(), request.getExpiresAt())) mismatches.add("expires_at");
        if (!Objects.equals(stored.getDedupeKey(), request.getDedupeKey())) mismatches.add("dedupe_key");
        if (!Objects.equals(stored.getRequiresResponse(), request.getRequiresResponse())) {
            mismatches.add("requires_response");
        }
        if (!mismatches.isEmpty()) {
            LOGGER.warn("主动事件幂等校验失败: event={}, fields={}",
                    stored.getEventId(), String.join(",", mismatches));
            throw new RenException("event_id已存在但事件内容不一致");
        }
    }

    private boolean sameSecond(Date left, Date right) {
        if (left == null || right == null) return left == right;
        return left.getTime() / 1000L == right.getTime() / 1000L;
    }

    private Map<String, Object> readMap(String value) {
        if (StringUtils.isBlank(value)) return Map.of();
        try {
            return Collections.unmodifiableMap(objectMapper.readValue(value, MAP_TYPE));
        } catch (JsonProcessingException exception) {
            throw new RenException("数据库JSON格式无效", exception);
        }
    }

    private Set<Topic> readTopics(String value) {
        if (StringUtils.isBlank(value)) return Set.of();
        try {
            List<String> values = objectMapper.readValue(value, new TypeReference<List<String>>() {});
            Set<Topic> topics = EnumSet.noneOf(Topic.class);
            for (String item : values) topics.add(Topic.fromWire(item));
            return Collections.unmodifiableSet(new LinkedHashSet<>(topics));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new RenException("数据库主题JSON格式无效", exception);
        }
    }

    private PreferenceView toPreference(ProactivePreferenceEntity entity) {
        return new PreferenceView(entity.getDeviceId(), entity.getMacAddress(), Mode.valueOf(entity.getMode()),
                entity.getDailyLimit(), entity.getQuietStart(), entity.getQuietEnd(),
                readTopics(entity.getAllowedTopics()), readTopics(entity.getBlockedTopics()),
                StringUtils.isBlank(entity.getPreviousMode()) ? null : Mode.valueOf(entity.getPreviousMode()),
                entity.getPreviousDailyLimit(), entity.getSilentUntil(), entity.getVersion(), entity.getUpdatedAt());
    }

    private EventView toEvent(ProactiveEventEntity entity) {
        return new EventView(entity.getDeviceId(), entity.getMacAddress(), entity.getEventId(),
                Topic.valueOf(entity.getTopic()), Priority.valueOf(entity.getPriority()), entity.getReason(),
                EventType.valueOf(entity.getEventType()), readMap(entity.getPayload()), entity.getCreatedAt(),
                entity.getExpiresAt(), entity.getDedupeKey(), Boolean.TRUE.equals(entity.getRequiresResponse()),
                DeliveryStatus.valueOf(entity.getDeliveryStatus()), Outcome.valueOf(entity.getOutcome()),
                entity.getDeliveredAt());
    }

    private HabitView toHabit(ProactiveHabitEntity entity) {
        return new HabitView(entity.getId(), entity.getDeviceId(), entity.getMacAddress(),
                HabitType.valueOf(entity.getHabitType()), entity.getHabitKey(), entity.getEvidenceCount(),
                entity.getFirstSeenAt(), entity.getLastSeenAt(), Boolean.TRUE.equals(entity.getSuggested()),
                Boolean.TRUE.equals(entity.getAccepted()), Boolean.TRUE.equals(entity.getDismissed()),
                readMap(entity.getPayload()));
    }
}
