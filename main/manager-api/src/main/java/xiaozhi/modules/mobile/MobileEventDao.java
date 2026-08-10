package xiaozhi.modules.mobile;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MobileEventDao {
    @Insert("INSERT IGNORE INTO ai_mobile_event (mobile_instance_id,event_id,dedupe_key,event_type,source_package,source_channel,event_state,summary,entities_json,evidence_json,privacy_level,status,reason_code,occurred_at,expires_at,created_at,updated_at) VALUES (#{mobileInstanceId},#{eventId},#{dedupeKey},#{eventType},#{sourcePackage},#{sourceChannel},#{eventState},#{summary},#{entitiesJson},#{evidenceJson},#{privacyLevel},#{status},#{reasonCode},#{occurredAt},#{expiresAt},#{createdAt},#{updatedAt})")
    int insertIgnore(MobileEventEntity entity);

    @org.apache.ibatis.annotations.Update("UPDATE ai_mobile_event SET event_state=#{eventState},summary=#{summary},entities_json=#{entitiesJson},evidence_json=#{evidenceJson},privacy_level=#{privacyLevel},occurred_at=#{occurredAt},expires_at=#{expiresAt},updated_at=#{updatedAt} WHERE mobile_instance_id=#{mobileInstanceId} AND dedupe_key=#{dedupeKey} AND occurred_at<=#{occurredAt}")
    int updateLatestState(MobileEventEntity entity);

    @Select("SELECT * FROM ai_mobile_event WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId} LIMIT 1")
    MobileEventEntity selectByEventId(@Param("instanceId") String instanceId, @Param("eventId") String eventId);

    @Select("SELECT * FROM ai_mobile_event WHERE mobile_instance_id=#{instanceId} ORDER BY updated_at DESC LIMIT #{limit}")
    List<MobileEventEntity> selectRecent(@Param("instanceId") String instanceId, @Param("limit") int limit);

    @Insert("INSERT INTO ai_mobile_event_audit (mobile_instance_id,event_id,status,reason_code,created_at) VALUES (#{mobileInstanceId},#{eventId},#{status},#{reasonCode},#{createdAt})")
    int insertAudit(MobileEventAuditEntity entity);

    @Select("SELECT * FROM ai_mobile_event_audit WHERE mobile_instance_id=#{instanceId} ORDER BY id DESC LIMIT #{limit}")
    List<MobileEventAuditEntity> selectRecentAudit(@Param("instanceId") String instanceId, @Param("limit") int limit);
}
