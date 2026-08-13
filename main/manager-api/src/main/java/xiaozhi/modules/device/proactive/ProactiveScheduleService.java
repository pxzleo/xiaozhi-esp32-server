package xiaozhi.modules.device.proactive;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.Action;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.Register;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.Trigger;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.View;

@Service
public class ProactiveScheduleService {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    private final DeviceDao deviceDao;
    private final ProactiveScheduleDao scheduleDao;
    private final ProactiveService proactiveService;
    private final ProactiveEventDao eventDao;
    public ProactiveScheduleService(DeviceDao deviceDao, ProactiveScheduleDao scheduleDao,
            ProactiveService proactiveService, ProactiveEventDao eventDao) {
        this.deviceDao=deviceDao; this.scheduleDao=scheduleDao; this.proactiveService=proactiveService;
        this.eventDao=eventDao;
    }

    @Transactional
    public View register(Register request) {
        DeviceEntity source = resolveMac(request.getSourceMacAddress());
        ProactiveScheduleEntity existing = scheduleDao.selectSourceForUpdate(source.getId(),
                request.getSourceScheduleId());
        String normalizedLabel=normalizeLabel(request.getKind(),request.getLabel());
        String normalizedLocation=StringUtils.trimToNull(request.getLocation());
        if (existing != null) {
            if (!existing.getKind().equals(request.getKind())
                    || !existing.getLabel().equals(normalizedLabel)
                    || existing.getScheduledAt().getTime()!=request.getScheduledAt()
                    || !existing.getRecurrence().equals(request.getRecurrence())
                    || !existing.getWeekdays().equals(json(request.getWeekdays()))
                    || !existing.getSections().equals(json(request.getSections()))
                    || !java.util.Objects.equals(existing.getLocation(),normalizedLocation)) {
                throw new RenException("同一源日程ID内容冲突");
            }
            return view(existing);
        }
        Date now=new Date();
        ProactiveScheduleEntity entity=new ProactiveScheduleEntity();
        entity.setId(UUID.randomUUID().toString()); entity.setUserId(source.getUserId());
        entity.setSourceDeviceId(source.getId()); entity.setSourceScheduleId(request.getSourceScheduleId());
        entity.setKind(request.getKind()); entity.setLabel(normalizedLabel);
        entity.setRecurrence(request.getRecurrence()); entity.setWeekdays(json(request.getWeekdays()));
        entity.setSections(json(request.getSections())); entity.setLocation(normalizedLocation);
        entity.setScheduledAt(new Date(request.getScheduledAt())); entity.setState("scheduled");
        entity.setVersion(1); entity.setCreatedAt(now); entity.setUpdatedAt(now);
        if (scheduleDao.insert(entity)!=1) throw new RenException("共享日程注册失败");
        return view(entity);
    }

    @Transactional
    public View trigger(String id, Trigger request) {
        ProactiveScheduleEntity entity=require(id);
        if (entity.getLastTriggeredAt()!=null
                && entity.getLastTriggeredAt().getTime()==request.getTriggeredAt()) {
            return view(entity, triggerEventId(entity.getId(), request.getTriggeredAt()));
        }
        boolean recurring = !"once".equals(entity.getRecurrence());
        if (!java.util.Set.of("scheduled","snoozed").contains(entity.getState())
                && !(recurring && "triggered".equals(entity.getState()))) {
            throw new RenException("共享日程状态不可触发");
        }
        entity.setState("triggered"); entity.setSnoozedUntil(null);
        entity.setLastTriggeredAt(new Date(request.getTriggeredAt()));
        entity.setVersion(entity.getVersion()+1); entity.setUpdatedAt(new Date());
        if (scheduleDao.updateById(entity)!=1) throw new RenException("共享日程触发失败");
        DeviceEntity source=deviceDao.selectById(entity.getSourceDeviceId());
        EventUpsert event=new EventUpsert();
        event.setMacAddress(source.getMacAddress());
        event.setEventId(triggerEventId(entity.getId(), request.getTriggeredAt()));
        event.setTopic("briefing".equals(entity.getKind()) ? Topic.NEWS : Topic.REMINDER);
        event.setPriority(Priority.HIGH);
        event.setReason("authoritative_schedule_triggered"); event.setEventType(EventType.REMINDER);
        Map<String,Object> payload=new java.util.HashMap<>();
        payload.put("title", "alarm".equals(entity.getKind()) ? "闹铃"
                : "briefing".equals(entity.getKind()) ? "每日简报" : "提醒");
        payload.put("message",entity.getLabel());
        payload.put("scheduled_at",java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(request.getTriggeredAt()),
                java.time.ZoneOffset.ofHours(8)).toString());
        payload.put("reference_id",entity.getId());
        if ("briefing".equals(entity.getKind())) {
            payload.put("action",String.join(",",readStrings(entity.getSections())));
            if (entity.getLocation()!=null) payload.put("source",entity.getLocation());
        }
        event.setPayload(payload);
        event.setCreatedAt(new Date(request.getTriggeredAt()));
        event.setExpiresAt(new Date(request.getTriggeredAt()+3600_000));
        event.setDedupeKey("schedule:"+entity.getId()+":"+request.getTriggeredAt());
        event.setRequiresResponse(false);
        proactiveService.upsertEvent(event);
        return view(entity, event.getEventId());
    }

    @Transactional
    public View triggerBySource(ProactiveScheduleDTOs.SourceTrigger request) {
        DeviceEntity source=resolveMac(request.getSourceMacAddress());
        ProactiveScheduleEntity entity=scheduleDao.selectSourceForUpdate(source.getId(),
                request.getSourceScheduleId());
        if (entity==null) {
            Date now=new Date(); entity=new ProactiveScheduleEntity();
            entity.setId(UUID.randomUUID().toString()); entity.setUserId(source.getUserId());
            entity.setSourceDeviceId(source.getId()); entity.setSourceScheduleId(request.getSourceScheduleId());
            String label=normalizeLabel(request.getKind(),request.getLabel());
            entity.setKind(request.getKind()); entity.setLabel(label);
            entity.setRecurrence("once"); entity.setWeekdays("[]");
            entity.setSections(json(request.getSections()));
            entity.setLocation(StringUtils.trimToNull(request.getLocation()));
            entity.setScheduledAt(new Date(request.getTriggeredAt())); entity.setState("scheduled");
            entity.setVersion(1); entity.setCreatedAt(now); entity.setUpdatedAt(now);
            if (scheduleDao.insert(entity)!=1) throw new RenException("共享日程补偿注册失败");
        } else if (!entity.getKind().equals(request.getKind())
                || StringUtils.isNotBlank(request.getLabel())
                   && !entity.getLabel().equals(request.getLabel().trim())
                || !entity.getSections().equals(json(request.getSections()))
                || !java.util.Objects.equals(entity.getLocation(),
                        StringUtils.trimToNull(request.getLocation()))) {
            throw new RenException("源日程触发内容冲突");
        }
        return trigger(entity.getId(), request);
    }

    @Transactional
    public View action(String id, Action request) {
        ProactiveScheduleEntity entity=require(id);
        boolean recurring=!"once".equals(entity.getRecurrence());
        String target=switch(request.getAction()) { case "stop"->recurring?"scheduled":"stopped"; case "snooze"->"snoozed";
            case "complete"->recurring?"scheduled":"completed"; default->throw new RenException("共享日程动作无效"); };
        Date snoozedUntil=request.getSnoozedUntil()==null?null:new Date(request.getSnoozedUntil());
        if (scheduleDao.actionCas(id, request.getVersion(), target, snoozedUntil)!=1)
            throw new RenException("共享日程已被其他终端处理");
        eventDao.dismissActiveScheduleEvents(entity.getUserId(), id);
        return view(require(id));
    }

    @Transactional
    public View actionBySource(ProactiveScheduleDTOs.SourceAction request) {
        DeviceEntity source=resolveMac(request.getSourceMacAddress());
        ProactiveScheduleEntity entity=scheduleDao.selectSourceForUpdate(source.getId(),
                request.getSourceScheduleId());
        if (entity==null) throw new RenException("共享日程不存在");
        String target=actionTarget(entity,request.getAction());
        Date snoozedUntil=request.getSnoozedUntil()==null?null:new Date(request.getSnoozedUntil());
        if (target.equals(entity.getState())
                && java.util.Objects.equals(snoozedUntil,entity.getSnoozedUntil())) {
            eventDao.dismissActiveScheduleEvents(entity.getUserId(),entity.getId());
            return view(entity);
        }
        if (scheduleDao.actionCas(entity.getId(),entity.getVersion(),target,snoozedUntil)!=1)
            throw new RenException("共享日程已被其他终端处理");
        eventDao.dismissActiveScheduleEvents(entity.getUserId(),entity.getId());
        return view(require(entity.getId()));
    }

    public java.util.List<View> active(Long userId) {
        return scheduleDao.selectActiveForUser(userId).stream().map(this::view).toList();
    }

    @Transactional
    public View actionForUser(Long userId, String id, Action request) {
        ProactiveScheduleEntity entity=require(id);
        if (!userId.equals(entity.getUserId())) throw new RenException("共享日程不存在");
        return action(id, request);
    }

    private ProactiveScheduleEntity require(String id) {
        ProactiveScheduleEntity entity=scheduleDao.selectForUpdate(id);
        if (entity==null) throw new RenException("共享日程不存在");
        return entity;
    }
    private DeviceEntity resolveMac(String mac) {
        if (StringUtils.isBlank(mac)) throw new RenException("源设备不存在");
        java.util.List<DeviceEntity> list=deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getMacAddress,mac).last("LIMIT 2"));
        if (list.size()!=1) throw new RenException("源设备不存在"); return list.getFirst();
    }
    private View view(ProactiveScheduleEntity e) { return view(e,null); }
    private View view(ProactiveScheduleEntity e,String eventId) { return new View(e.getId(),e.getUserId(),
            e.getSourceDeviceId(),e.getSourceScheduleId(),e.getKind(),e.getLabel(),e.getRecurrence(),
            readWeekdays(e.getWeekdays()),readStrings(e.getSections()),e.getLocation(),
            e.getScheduledAt().getTime(),
            e.getState(),e.getLastTriggeredAt()==null?null:e.getLastTriggeredAt().getTime(),
            e.getSnoozedUntil()==null?null:e.getSnoozedUntil().getTime(),e.getVersion(),eventId); }
    private String triggerEventId(String id,long triggeredAt) {
        return "schedule-"+id+"-"+triggeredAt;
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new RenException("重复日程序列化失败",e); } }
    private java.util.List<Integer> readWeekdays(String value) { try { return mapper.readValue(value,
            mapper.getTypeFactory().constructCollectionType(java.util.List.class,Integer.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new RenException("重复日程数据损坏",e); } }
    private java.util.List<String> readStrings(String value) { try { return mapper.readValue(value,
            mapper.getTypeFactory().constructCollectionType(java.util.List.class,String.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new RenException("简报栏目数据损坏",e); } }
    private String normalizeLabel(String kind,String label) {
        return "briefing".equals(kind) && StringUtils.isBlank(label) ? "每日简报" : label.trim();
    }
    private String actionTarget(ProactiveScheduleEntity entity,String action) {
        boolean recurring=!"once".equals(entity.getRecurrence());
        return switch(action) { case "stop"->recurring?"scheduled":"stopped";
            case "snooze"->"snoozed"; case "complete"->recurring?"scheduled":"completed";
            default->throw new RenException("共享日程动作无效"); };
    }
}
