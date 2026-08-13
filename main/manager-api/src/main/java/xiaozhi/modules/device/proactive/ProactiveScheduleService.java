package xiaozhi.modules.device.proactive;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.*;

@Service
public class ProactiveScheduleService {
    private static final ZoneOffset SCHEDULE_ZONE = ZoneOffset.ofHours(8);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeviceDao deviceDao;
    private final ProactiveScheduleDao scheduleDao;
    private final ProactiveService proactiveService;
    private final ProactiveEventDao eventDao;
    private final ProactiveScheduleRevisionDao revisionDao;
    private final ProactiveScheduleActionDao actionDao;
    private final ProactiveDeliveryRoutingService routingService;

    @Autowired
    public ProactiveScheduleService(DeviceDao deviceDao, ProactiveScheduleDao scheduleDao,
            ProactiveService proactiveService, ProactiveEventDao eventDao,
            ProactiveScheduleRevisionDao revisionDao, ProactiveScheduleActionDao actionDao,
            ProactiveDeliveryRoutingService routingService) {
        this.deviceDao=deviceDao; this.scheduleDao=scheduleDao; this.proactiveService=proactiveService;
        this.eventDao=eventDao; this.revisionDao=revisionDao; this.actionDao=actionDao;
        this.routingService=routingService;
    }

    ProactiveScheduleService(DeviceDao deviceDao, ProactiveScheduleDao scheduleDao,
            ProactiveService proactiveService, ProactiveEventDao eventDao) {
        this(deviceDao,scheduleDao,proactiveService,eventDao,null,null,null);
    }

    @Transactional
    public View register(Register request) {
        DeviceEntity source=resolveMac(request.getSourceMacAddress());
        lockUser(source.getUserId());
        ProactiveScheduleEntity existing=scheduleDao.selectSourceForUpdate(source.getId(),
                request.getSourceScheduleId());
        String label=normalizeLabel(request.getKind(),request.getLabel());
        String location=StringUtils.trimToNull(request.getLocation());
        if (existing!=null) {
            if (!sameRegistration(existing,request,label,location))
                throw new RenException("同一源日程ID内容冲突");
            return view(existing);
        }
        Date now=new Date();
        ProactiveScheduleEntity entity=new ProactiveScheduleEntity();
        entity.setId(UUID.randomUUID().toString()); entity.setUserId(source.getUserId());
        entity.setSourceDeviceId(source.getId()); entity.setSourceScheduleId(request.getSourceScheduleId());
        entity.setKind(request.getKind()); entity.setLabel(label); entity.setRecurrence(request.getRecurrence());
        entity.setWeekdays(json(request.getWeekdays())); entity.setSections(json(request.getSections()));
        entity.setLocation(location); entity.setScheduledAt(new Date(request.getScheduledAt()));
        entity.setNextTriggerAt(new Date(request.getScheduledAt())); entity.setState("scheduled");
        entity.setVersion(1); entity.setSyncRevision(0L); entity.setCreatedAt(now); entity.setUpdatedAt(now);
        if (scheduleDao.insert(entity)!=1) throw new RenException("共享日程注册失败");
        publishRevision(entity);
        return view(entity);
    }

    @Transactional
    public View trigger(String id, Trigger request) {
        DeviceEntity requester=StringUtils.isBlank(request.getRequesterMacAddress())?null
                :resolveMac(request.getRequesterMacAddress());
        lockScheduleOwner(id);
        ProactiveScheduleEntity entity=require(id);
        if (requester!=null && !requester.getUserId().equals(entity.getUserId()))
            throw new RenException("共享日程不存在");
        return triggerLocked(entity,request.getTriggeredAt());
    }

    @Transactional
    public View triggerBySource(SourceTrigger request) {
        DeviceEntity source=resolveMac(request.getSourceMacAddress());
        lockUser(source.getUserId());
        ProactiveScheduleEntity entity=scheduleDao.selectSourceForUpdate(source.getId(),request.getSourceScheduleId());
        if (entity==null) {
            Register register=new Register(); register.setSourceMacAddress(request.getSourceMacAddress());
            register.setSourceScheduleId(request.getSourceScheduleId()); register.setKind(request.getKind());
            register.setLabel(request.getLabel()); register.setScheduledAt(request.getTriggeredAt());
            register.setRecurrence("once"); register.setWeekdays(List.of());
            register.setSections(request.getSections()); register.setLocation(request.getLocation());
            register(register);
            entity=scheduleDao.selectSourceForUpdate(source.getId(),request.getSourceScheduleId());
        } else if (!entity.getKind().equals(request.getKind())
                || StringUtils.isNotBlank(request.getLabel()) && !entity.getLabel().equals(request.getLabel().trim())
                || !entity.getSections().equals(json(request.getSections()))
                || !Objects.equals(entity.getLocation(),StringUtils.trimToNull(request.getLocation()))) {
            throw new RenException("源日程触发内容冲突");
        }
        return triggerLocked(entity,request.getTriggeredAt());
    }

    @Transactional
    public boolean processOneDue() {
        ProactiveScheduleEntity candidate=scheduleDao.selectDueCandidate();
        if (candidate==null) return false;
        if (routingService!=null) routingService.lockUser(candidate.getUserId());
        ProactiveScheduleEntity entity=scheduleDao.selectDueForUpdate(candidate.getId());
        if (entity==null) return false;
        Date databaseNow=scheduleDao.selectDatabaseNow();
        Date due=entity.getSnoozedUntil()!=null ? entity.getSnoozedUntil() : entity.getNextTriggerAt();
        if (databaseNow==null || due==null || due.after(databaseNow)) return false;
        triggerLocked(entity,due.getTime());
        return true;
    }

    private View triggerLocked(ProactiveScheduleEntity entity,long triggeredAt) {
        if (entity.getDeletedAt()!=null) throw new RenException("共享日程不存在");
        validateStoredLabel(entity);
        if (entity.getLastTriggeredAt()!=null && entity.getLastTriggeredAt().getTime()==triggeredAt)
            return view(entity,triggerEventId(entity.getId(),triggeredAt));
        if (entity.getLastTriggeredAt()!=null && entity.getLastTriggeredAt().getTime()>triggeredAt)
            throw new RenException("共享日程触发时间早于权威状态");
        Date expectedOccurrence=entity.getSnoozedUntil()!=null
                ?entity.getSnoozedUntil():entity.getNextTriggerAt();
        if (expectedOccurrence==null || expectedOccurrence.getTime()!=triggeredAt)
            throw new RenException("共享日程触发时间不是当前权威期次");
        boolean recurring=!"once".equals(entity.getRecurrence());
        if (!Set.of("scheduled","snoozed").contains(entity.getState())
                && !(recurring && "triggered".equals(entity.getState())))
            throw new RenException("共享日程状态不可触发");
        Date previousNext=entity.getNextTriggerAt();
        boolean wasSnoozed="snoozed".equals(entity.getState());
        entity.setLastTriggeredAt(new Date(triggeredAt)); entity.setSnoozedUntil(null);
        entity.setState(recurring?"scheduled":"triggered");
        entity.setNextTriggerAt(recurring && wasSnoozed && previousNext!=null
                && previousNext.after(new Date(triggeredAt)) ? previousNext
                : recurring?nextOccurrence(entity,new Date(triggeredAt)):null);
        entity.setVersion(entity.getVersion()+1); entity.setUpdatedAt(new Date());
        if (scheduleDao.updateById(entity)!=1) throw new RenException("共享日程触发失败");
        publishRevision(entity);
        EventUpsert event=event(entity,triggeredAt);
        proactiveService.upsertEvent(event);
        return view(entity,event.getEventId());
    }

    @Transactional
    public View action(String id, Action request) {
        DeviceEntity requester=StringUtils.isBlank(request.getRequesterMacAddress())?null
                :resolveMac(request.getRequesterMacAddress());
        lockScheduleOwner(id);
        ProactiveScheduleEntity entity=scheduleDao.selectForUpdate(id);
        if (requester!=null && (entity==null || !requester.getUserId().equals(entity.getUserId())))
            throw new RenException("共享日程不存在");
        return actionLocked(entity,request);
    }

    private View actionLocked(ProactiveScheduleEntity entity,Action request) {
        if (entity==null) throw new RenException("共享日程不存在");
        if (entity.getDeletedAt()!=null) {
            if ("delete".equals(request.getAction())) return view(entity);
            throw new RenException("共享日程不存在");
        }
        if (request.getVersion()!=null && !request.getVersion().equals(entity.getVersion()))
            throw new RenException("共享日程已被其他终端处理");
        if (request.getVersion()==null && sameLatestAction(entity,request.getAction(),
                request.getSnoozedUntil())) return view(entity);
        return applyAction(entity,request.getAction(),request.getSnoozedUntil());
    }

    @Transactional
    public View actionBySource(SourceAction request) {
        DeviceEntity source=resolveMac(request.getSourceMacAddress());
        lockUser(source.getUserId());
        ProactiveScheduleEntity entity=scheduleDao.selectSourceForUpdate(source.getId(),request.getSourceScheduleId());
        if (entity==null) throw new RenException("共享日程不存在");
        if (entity.getDeletedAt()!=null) {
            if ("delete".equals(request.getAction())) return view(entity);
            throw new RenException("共享日程不存在");
        }
        if (sameLatestAction(entity,request.getAction(),request.getSnoozedUntil())) return view(entity);
        if (("stop".equals(request.getAction()) && "stopped".equals(entity.getState()))
                || ("complete".equals(request.getAction()) && "completed".equals(entity.getState()))
                || ("snooze".equals(request.getAction()) && "snoozed".equals(entity.getState())
                    && Objects.equals(entity.getSnoozedUntil(),new Date(request.getSnoozedUntil())))) {
            eventDao.dismissActiveScheduleEvents(entity.getUserId(),entity.getId());
            return view(entity);
        }
        return applyAction(entity,request.getAction(),request.getSnoozedUntil());
    }

    private View applyAction(ProactiveScheduleEntity entity,String action,Long snoozedUntilMillis) {
        boolean recurring=!"once".equals(entity.getRecurrence());
        String target=switch(action) {
            case "stop" -> recurring?"scheduled":"stopped";
            case "snooze" -> "snoozed";
            case "complete" -> recurring?"scheduled":"completed";
            case "delete" -> "deleted";
            default -> throw new RenException("共享日程动作无效");
        };
        Date snoozed=snoozedUntilMillis==null?null:new Date(snoozedUntilMillis);
        Date now=new Date();
        entity.setState(target); entity.setSnoozedUntil(snoozed);
        if ("delete".equals(action)) {
            entity.setDeletedAt(now); entity.setNextTriggerAt(null);
        } else if (!recurring) {
            entity.setNextTriggerAt("snooze".equals(action)?snoozed:null);
        }
        entity.setVersion(entity.getVersion()+1); entity.setUpdatedAt(now);
        if (scheduleDao.updateById(entity)!=1) throw new RenException("共享日程已被其他终端处理");
        publishRevision(entity); publishAction(entity,action);
        eventDao.dismissActiveScheduleEvents(entity.getUserId(),entity.getId());
        return view(entity);
    }

    public List<View> active(Long userId) {
        return scheduleDao.selectActiveForUser(userId).stream().map(this::view).toList();
    }

    @Transactional
    public View actionForUser(Long userId,String id,Action request) {
        lockUser(userId);
        ProactiveScheduleEntity entity=scheduleDao.selectForUpdate(id);
        if (entity==null || entity.getDeletedAt()!=null) throw new RenException("共享日程不存在");
        if (!userId.equals(entity.getUserId())) throw new RenException("共享日程不存在");
        if (request.getVersion()==null) throw new RenException("共享日程版本不能为空");
        return actionLocked(entity,request);
    }

    public SyncResponse sync(String mac,long since,int limit) {
        DeviceEntity requester=resolveMac(mac);
        List<ProactiveScheduleEntity> rows=scheduleDao.selectChanges(requester.getUserId(),since,limit+1);
        boolean more=rows.size()>limit;
        if (more) rows=rows.subList(0,limit);
        List<SyncChange> changes=rows.stream().map(row->syncChange(requester,row)).toList();
        long cursor=changes.isEmpty()?since:changes.getLast().revision();
        return new SyncResponse(1,since==0,cursor,more,changes);
    }

    public ActionsResponse actions(String mac,long after,int limit) {
        DeviceEntity requester=resolveMac(mac);
        Long acknowledged=actionDao.acknowledged(requester.getId());
        long effectiveAfter=Math.max(after,acknowledged==null?0:acknowledged);
        List<ProactiveScheduleActionEntity> rows=actionDao.selectAfter(requester.getUserId(),effectiveAfter,limit+1);
        boolean more=rows.size()>limit;
        if (more) rows=rows.subList(0,limit);
        Map<String,String> macs=new HashMap<>();
        List<ActionChange> actions=rows.stream().map(row->{
            String sourceMac=macs.computeIfAbsent(row.getSourceDeviceId(),
                    id->sourceMac(id,row.getUserId()));
            return new ActionChange(1,row.getRevision(),row.getActionId(),row.getScheduleId(),sourceMac,
                    requester.getId().equals(row.getSourceDeviceId()),row.getSourceScheduleId(),
                    row.getScheduleVersion(),row.getAction(),millis(row.getSnoozedUntil()),
                    millis(row.getNextTriggerAt()),row.getCreatedAt().getTime());
        }).toList();
        long cursor=actions.isEmpty()?effectiveAfter:actions.getLast().revision();
        return new ActionsResponse(1,cursor,more,actions);
    }

    @Transactional
    public AckResponse acknowledge(String mac,AckRequest request) {
        DeviceEntity requester=resolveMac(mac);
        long high=actionDao.highWater(requester.getUserId());
        if (request.getThroughRevision()>high) throw new RenException("日程动作确认游标超出账号范围");
        actionDao.acknowledge(requester.getId(),request.getThroughRevision());
        Long acknowledged=actionDao.acknowledged(requester.getId());
        return new AckResponse(1,acknowledged==null?0:acknowledged);
    }

    private SyncChange syncChange(DeviceEntity requester,ProactiveScheduleEntity row) {
        validateStoredLabel(row);
        String sourceMac=sourceMac(row.getSourceDeviceId(),row.getUserId());
        boolean deleted=row.getDeletedAt()!=null;
        ProactiveEventEntity event=deleted?null:scheduleDao.selectLatestEvent(requester.getId(),row.getId());
        return new SyncChange(row.getSyncRevision(),deleted?"delete":"upsert",row.getId(),sourceMac,
                requester.getId().equals(row.getSourceDeviceId()),row.getSourceScheduleId(),row.getVersion(),
                deleted?null:row.getKind(),deleted?null:row.getLabel(),deleted?null:row.getRecurrence(),
                deleted?null:readWeekdays(row.getWeekdays()),deleted?null:readStrings(row.getSections()),
                deleted?null:row.getLocation(),deleted?null:millis(row.getScheduledAt()),
                deleted?null:millis(row.getNextTriggerAt()),deleted?null:row.getState(),
                deleted?null:millis(row.getLastTriggeredAt()),deleted?null:millis(row.getSnoozedUntil()),
                event==null?null:event.getEventId(),event==null?null:event.getDeliveryStatus().toLowerCase(),
                row.getUpdatedAt().getTime());
    }

    private void publishRevision(ProactiveScheduleEntity entity) {
        if (revisionDao==null) return;
        ProactiveScheduleRevisionEntity revision=new ProactiveScheduleRevisionEntity();
        revision.setUserId(entity.getUserId()); revision.setScheduleId(entity.getId());
        revision.setCreatedAt(new Date());
        if (revisionDao.insert(revision)!=1 || revision.getRevision()==null)
            throw new RenException("共享日程增量版本写入失败");
        entity.setSyncRevision(revision.getRevision());
        if (scheduleDao.updateById(entity)!=1) throw new RenException("共享日程增量版本更新失败");
    }

    private void publishAction(ProactiveScheduleEntity entity,String action) {
        if (actionDao==null) return;
        ProactiveScheduleActionEntity row=new ProactiveScheduleActionEntity();
        row.setActionId(UUID.randomUUID().toString()); row.setUserId(entity.getUserId());
        row.setScheduleId(entity.getId()); row.setSourceDeviceId(entity.getSourceDeviceId());
        row.setSourceScheduleId(entity.getSourceScheduleId()); row.setScheduleVersion(entity.getVersion());
        row.setAction(action); row.setSnoozedUntil(entity.getSnoozedUntil());
        row.setNextTriggerAt(entity.getNextTriggerAt()); row.setCreatedAt(new Date());
        if (actionDao.insert(row)!=1) throw new RenException("共享日程动作写入失败");
    }

    private boolean sameLatestAction(ProactiveScheduleEntity entity,String action,Long snoozedUntil) {
        if (actionDao==null) return false;
        ProactiveScheduleActionEntity latest=actionDao.selectLatestForSchedule(entity.getId());
        return latest!=null && latest.getScheduleVersion().equals(entity.getVersion())
                && latest.getAction().equals(action)
                && Objects.equals(millis(latest.getSnoozedUntil()),snoozedUntil);
    }

    private EventUpsert event(ProactiveScheduleEntity entity,long triggeredAt) {
        DeviceEntity source=deviceDao.selectById(entity.getSourceDeviceId());
        if (source==null || !entity.getUserId().equals(source.getUserId())) throw new RenException("源设备不存在");
        EventUpsert event=new EventUpsert(); event.setMacAddress(source.getMacAddress());
        event.setEventId(triggerEventId(entity.getId(),triggeredAt));
        event.setTopic("briefing".equals(entity.getKind())?Topic.NEWS:Topic.REMINDER);
        event.setPriority(Priority.HIGH); event.setReason("authoritative_schedule_triggered");
        event.setEventType(EventType.REMINDER);
        Map<String,Object> payload=new HashMap<>();
        payload.put("title","alarm".equals(entity.getKind())?"闹铃":"briefing".equals(entity.getKind())?"每日简报":"提醒");
        payload.put("message",entity.getLabel());
        payload.put("scheduled_at",LocalDateTime.ofInstant(Instant.ofEpochMilli(triggeredAt),SCHEDULE_ZONE).toString());
        payload.put("reference_id",entity.getId());
        if ("briefing".equals(entity.getKind())) {
            payload.put("action",String.join(",",readStrings(entity.getSections())));
            if (entity.getLocation()!=null) payload.put("source",entity.getLocation());
        }
        event.setPayload(payload); event.setCreatedAt(new Date(triggeredAt));
        event.setExpiresAt(new Date(Math.max(System.currentTimeMillis(),triggeredAt)+3_600_000));
        event.setDedupeKey("schedule:"+entity.getId()+":"+triggeredAt); event.setRequiresResponse(false);
        return event;
    }

    private Date nextOccurrence(ProactiveScheduleEntity entity,Date after) {
        LocalDateTime cursor=LocalDateTime.ofInstant(after.toInstant(),SCHEDULE_ZONE);
        Set<Integer> weekdays=Set.copyOf(readWeekdays(entity.getWeekdays()));
        for (int days=1;days<=8;days++) {
            LocalDateTime candidate=cursor.plusDays(days);
            int day=candidate.getDayOfWeek().getValue();
            boolean matches=switch(entity.getRecurrence()) {
                case "daily" -> true;
                case "weekdays" -> day<=DayOfWeek.FRIDAY.getValue();
                case "weekends" -> day>=DayOfWeek.SATURDAY.getValue();
                case "weekly" -> weekdays.contains(day);
                default -> false;
            };
            if (matches) return Date.from(candidate.toInstant(SCHEDULE_ZONE));
        }
        throw new RenException("重复日程规则无法计算下一次时间");
    }

    private ProactiveScheduleEntity require(String id) {
        ProactiveScheduleEntity entity=scheduleDao.selectForUpdate(id);
        if (entity==null || entity.getDeletedAt()!=null) throw new RenException("共享日程不存在");
        return entity;
    }
    private void lockUser(Long userId) {
        if (routingService!=null) routingService.lockUser(userId);
    }
    private void lockScheduleOwner(String id) {
        if (routingService==null) return;
        ProactiveScheduleEntity candidate=scheduleDao.selectById(id);
        if (candidate==null) throw new RenException("共享日程不存在");
        lockUser(candidate.getUserId());
    }
    private DeviceEntity resolveMac(String mac) {
        if (StringUtils.isBlank(mac)) throw new RenException("源设备不存在");
        List<DeviceEntity> rows=deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getMacAddress,mac).last("LIMIT 2"));
        if (rows.size()!=1 || rows.getFirst().getUserId()==null) throw new RenException("源设备不存在");
        return rows.getFirst();
    }
    private String sourceMac(String deviceId,Long expectedUserId) {
        DeviceEntity source=deviceDao.selectById(deviceId);
        if (source==null || !expectedUserId.equals(source.getUserId()))
            throw new RenException("日程源设备不存在");
        return source.getMacAddress();
    }
    private boolean sameRegistration(ProactiveScheduleEntity e,Register r,String label,String location) {
        return e.getDeletedAt()==null && e.getKind().equals(r.getKind()) && e.getLabel().equals(label)
                && e.getScheduledAt().getTime()==r.getScheduledAt() && e.getRecurrence().equals(r.getRecurrence())
                && e.getWeekdays().equals(json(r.getWeekdays())) && e.getSections().equals(json(r.getSections()))
                && Objects.equals(e.getLocation(),location);
    }
    private View view(ProactiveScheduleEntity e){return view(e,null);}
    private View view(ProactiveScheduleEntity e,String eventId){validateStoredLabel(e); return new View(e.getId(),e.getUserId(),
            e.getSourceDeviceId(),e.getSourceScheduleId(),e.getKind(),e.getLabel(),e.getRecurrence(),
            readWeekdays(e.getWeekdays()),readStrings(e.getSections()),e.getLocation(),e.getScheduledAt().getTime(),
            e.getState(),millis(e.getNextTriggerAt()),millis(e.getLastTriggeredAt()),millis(e.getSnoozedUntil()),
            e.getVersion(),eventId);}
    private String triggerEventId(String id,long at){return "schedule-"+id+"-"+at;}
    private Long millis(Date value){return value==null?null:value.getTime();}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new RenException("重复日程序列化失败",e);}}
    private List<Integer> readWeekdays(String value){try{return mapper.readValue(value,mapper.getTypeFactory().constructCollectionType(List.class,Integer.class));}catch(JsonProcessingException e){throw new RenException("重复日程数据损坏",e);}}
    private List<String> readStrings(String value){try{return mapper.readValue(value,mapper.getTypeFactory().constructCollectionType(List.class,String.class));}catch(JsonProcessingException e){throw new RenException("简报栏目数据损坏",e);}}
    private String normalizeLabel(String kind,String label){
        String normalized="briefing".equals(kind)&&StringUtils.isBlank(label)?"每日简报":label.trim();
        if (normalized.codePointCount(0,normalized.length())>80)
            throw new RenException("共享日程标题超过80字符");
        return normalized;
    }
    private void validateStoredLabel(ProactiveScheduleEntity entity) {
        String label=entity.getLabel();
        if (label==null || label.isBlank() || label.codePointCount(0,label.length())>80)
            throw new RenException("共享日程标题数据无效");
    }
}
