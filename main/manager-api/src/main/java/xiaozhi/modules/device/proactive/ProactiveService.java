package xiaozhi.modules.device.proactive;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventStatusUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitObserve;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceView;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.HabitType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

@Service
public class ProactiveService {
    private static final Set<String> EVENT_PAYLOAD_KEYS = Set.of(
            "title", "message", "reference_id", "scheduled_at", "action", "source");
    private static final Set<String> HABIT_PAYLOAD_KEYS = Set.of(
            "description", "suggested_mode", "suggested_time", "topic");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DeviceDao deviceDao;
    private final ProactivePreferenceDao preferenceDao;
    private final ProactiveEventDao eventDao;
    private final ProactiveHabitDao habitDao;
    private final ObjectMapper objectMapper;

    public ProactiveService(DeviceDao deviceDao, ProactivePreferenceDao preferenceDao,
            ProactiveEventDao eventDao, ProactiveHabitDao habitDao, ObjectMapper objectMapper) {
        this.deviceDao = deviceDao;
        this.preferenceDao = preferenceDao;
        this.eventDao = eventDao;
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
        DeviceEntity device = resolveByMac(request.getMacAddress());
        validatePayload(request.getPayload(), EVENT_PAYLOAD_KEYS, "event payload");
        ProactiveEventEntity existing = eventDao.selectByDeviceAndEventId(device.getId(), request.getEventId());
        if (existing != null) {
            verifyIdempotentEvent(existing, request);
            return toEvent(existing);
        }
        Date now = new Date();
        ProactiveEventEntity entity = new ProactiveEventEntity();
        entity.setDeviceId(device.getId());
        entity.setMacAddress(device.getMacAddress());
        entity.setEventId(request.getEventId());
        entity.setTopic(request.getTopic().name());
        entity.setPriority(request.getPriority().name());
        entity.setReason(request.getReason());
        entity.setEventType(request.getEventType().name());
        entity.setPayload(writeJson(request.getPayload()));
        entity.setCreatedAt(request.getCreatedAt());
        entity.setExpiresAt(request.getExpiresAt());
        entity.setDedupeKey(request.getDedupeKey());
        entity.setRequiresResponse(request.getRequiresResponse());
        entity.setDeliveryStatus(DeliveryStatus.PENDING.name());
        entity.setOutcome(Outcome.NONE.name());
        entity.setUpdatedAt(now);
        try {
            eventDao.insert(entity);
        } catch (DuplicateKeyException exception) {
            ProactiveEventEntity concurrent = eventDao.selectByDeviceAndEventId(
                    device.getId(), request.getEventId());
            if (concurrent == null) throw new RenException("主动事件写入冲突", exception);
            verifyIdempotentEvent(concurrent, request);
            return toEvent(concurrent);
        }
        ProactiveEventEntity stored = eventDao.selectByDeviceAndEventId(device.getId(), request.getEventId());
        if (stored == null) throw new RenException("主动事件写入失败");
        return toEvent(stored);
    }

    @Transactional
    public EventView updateEventStatus(String eventId, EventStatusUpdate request) {
        DeviceEntity device = resolveByMac(request.getMacAddress());
        if (eventDao.updateStatus(device.getId(), eventId, request.getDeliveryStatus().name(),
                request.getOutcome().name(), new Date()) != 1) {
            throw new RenException("主动事件不存在");
        }
        ProactiveEventEntity event = eventDao.selectByDeviceAndEventId(device.getId(), eventId);
        return toEvent(event);
    }

    @Transactional
    public HabitView observeHabit(HabitObserve request) {
        DeviceEntity device = resolveByMac(request.getMacAddress());
        validatePayload(request.getPayload(), HABIT_PAYLOAD_KEYS, "habit payload");
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

    public PageData<EventView> events(Long userId, String deviceId, Topic topic,
            DeliveryStatus status, EventType eventType, int page, int limit) {
        if (deviceId != null) requireOwned(userId, deviceId);
        int safePage = requireRange(page, 1, 100_000, "page");
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
        return entity;
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
            case ACTIVE -> 3;
            case AGGRESSIVE -> 5;
            case TODAY_SILENT -> 0;
        };
    }

    private void validatePayload(Map<String, Object> payload, Set<String> allowedKeys, String name) {
        if (payload == null || !allowedKeys.containsAll(payload.keySet())) {
            throw new RenException(name + "包含不支持的键");
        }
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new RenException(name + "仅允许标量值");
            }
            if (value instanceof String text && text.length() > 512) {
                throw new RenException(name + "字段长度不能超过512");
            }
        }
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
        boolean equal = Objects.equals(stored.getTopic(), request.getTopic().name())
                && Objects.equals(stored.getPriority(), request.getPriority().name())
                && Objects.equals(stored.getReason(), request.getReason())
                && Objects.equals(stored.getEventType(), request.getEventType().name())
                && Objects.equals(readMap(stored.getPayload()), request.getPayload())
                && sameSecond(stored.getCreatedAt(), request.getCreatedAt())
                && sameSecond(stored.getExpiresAt(), request.getExpiresAt())
                && Objects.equals(stored.getDedupeKey(), request.getDedupeKey())
                && Objects.equals(stored.getRequiresResponse(), request.getRequiresResponse());
        if (!equal) throw new RenException("event_id已存在但事件内容不一致");
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
