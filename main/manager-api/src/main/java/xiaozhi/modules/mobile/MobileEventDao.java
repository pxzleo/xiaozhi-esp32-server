package xiaozhi.modules.mobile;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MobileEventDao {
    @Insert("INSERT IGNORE INTO ai_mobile_event (mobile_instance_id,event_id,dedupe_key,event_type,source_package,source_channel,event_state,summary,entities_json,evidence_json,privacy_level,status,processing_status,reason_code,occurred_at,expires_at,created_at,updated_at) VALUES (#{mobileInstanceId},#{eventId},#{dedupeKey},#{eventType},#{sourcePackage},#{sourceChannel},#{eventState},#{summary},#{entitiesJson},#{evidenceJson},#{privacyLevel},#{status},#{processingStatus},#{reasonCode},#{occurredAt},#{expiresAt},#{createdAt},#{updatedAt})")
    int insertIgnore(MobileEventEntity entity);

    @org.apache.ibatis.annotations.Update("""
            UPDATE ai_mobile_event SET event_state=#{eventState},summary=#{summary},
                entities_json=#{entitiesJson},evidence_json=#{evidenceJson},privacy_level=#{privacyLevel},
                occurred_at=#{occurredAt},expires_at=#{expiresAt},processing_status='received',
                reason_code=NULL,category=NULL,severity=NULL,confidence=NULL,spoken_summary=NULL,
                processing_lease_owner=NULL,processing_lease_token=NULL,
                processing_lease_until=NULL,next_attempt_at=NULL,processed_at=NULL,updated_at=#{updatedAt}
            WHERE mobile_instance_id=#{mobileInstanceId} AND dedupe_key=#{dedupeKey}
              AND occurred_at<#{occurredAt}
            """)
    int updateLatestState(MobileEventEntity entity);

    @Update("""
            UPDATE ai_device_proactive_event copies
            INNER JOIN ai_device_proactive_event source
                ON source.delivery_group_key=copies.delivery_group_key
            INNER JOIN ai_mobile_instance mi ON mi.device_id=source.device_id
            INNER JOIN ai_mobile_event me
                ON me.mobile_instance_id=mi.mobile_instance_id
               AND me.proactive_event_id=source.event_id
            SET copies.expires_at=CASE
                    WHEN copies.delivery_status='CLAIMED' THEN CURRENT_TIMESTAMP(3)
                    ELSE copies.expires_at END,
                copies.outcome=CASE
                    WHEN copies.delivery_status='PENDING' THEN 'DISMISSED'
                    ELSE copies.outcome END,
                copies.delivery_status=CASE
                    WHEN copies.delivery_status='PENDING' THEN 'DISMISSED'
                    ELSE copies.delivery_status END,
                copies.updated_at=CURRENT_TIMESTAMP(3)
            WHERE me.mobile_instance_id=#{instanceId} AND me.dedupe_key=#{dedupeKey}
              AND copies.delivery_status IN ('PENDING','CLAIMED')
            """)
    int supersedeUndeliveredMobileAlerts(@Param("instanceId") String instanceId,
            @Param("dedupeKey") String dedupeKey);

    @Select("SELECT * FROM ai_mobile_event WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId} LIMIT 1")
    MobileEventEntity selectByEventId(@Param("instanceId") String instanceId, @Param("eventId") String eventId);

    @Select("SELECT * FROM ai_mobile_event WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId} LIMIT 1 FOR UPDATE")
    MobileEventEntity selectByEventIdForUpdate(@Param("instanceId") String instanceId,
            @Param("eventId") String eventId);

    @Select("SELECT * FROM ai_mobile_event WHERE mobile_instance_id=#{instanceId} ORDER BY updated_at DESC LIMIT #{limit}")
    List<MobileEventEntity> selectRecent(@Param("instanceId") String instanceId, @Param("limit") int limit);

    @Insert("INSERT INTO ai_mobile_event_audit (mobile_instance_id,event_id,status,reason_code,created_at) VALUES (#{mobileInstanceId},#{eventId},#{status},#{reasonCode},#{createdAt})")
    int insertAudit(MobileEventAuditEntity entity);

    @Select("SELECT * FROM ai_mobile_event_audit WHERE mobile_instance_id=#{instanceId} ORDER BY id DESC LIMIT #{limit}")
    List<MobileEventAuditEntity> selectRecentAudit(@Param("instanceId") String instanceId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM ai_mobile_event
            WHERE processing_status IN ('received','error')
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
              AND (processing_lease_until IS NULL OR processing_lease_until < CURRENT_TIMESTAMP(3))
              AND expires_at > CURRENT_TIMESTAMP(3)
            ORDER BY occurred_at, mobile_instance_id, event_id
            LIMIT #{limit}
            """)
    List<MobileEventEntity> selectProcessingCandidates(@Param("limit") int limit);

    @Update("""
            UPDATE ai_mobile_event
            SET processing_status='ignored', reason_code='event_expired',
                processing_lease_owner=NULL, processing_lease_token=NULL,
                processing_lease_until=NULL, processed_at=CURRENT_TIMESTAMP(3),
                updated_at=CURRENT_TIMESTAMP(3)
            WHERE processing_status IN ('received','error')
              AND expires_at <= CURRENT_TIMESTAMP(3)
              AND (processing_lease_until IS NULL OR processing_lease_until < CURRENT_TIMESTAMP(3))
            """)
    int ignoreExpired();

    @Update("""
            UPDATE ai_mobile_event
            SET processing_lease_owner=#{owner}, processing_lease_token=#{token},
                processing_lease_until=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 120 SECOND),
                processing_attempt=processing_attempt+1, updated_at=CURRENT_TIMESTAMP(3)
            WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId}
              AND processing_status IN ('received','error')
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
              AND (processing_lease_until IS NULL OR processing_lease_until < CURRENT_TIMESTAMP(3))
              AND expires_at > CURRENT_TIMESTAMP(3)
            """)
    int claimProcessing(@Param("instanceId") String instanceId, @Param("eventId") String eventId,
            @Param("owner") String owner, @Param("token") String token);

    @Update("""
            UPDATE ai_mobile_event SET processing_status='ignored', reason_code=#{reason},
                processing_lease_owner=NULL, processing_lease_token=NULL, processing_lease_until=NULL,
                processed_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3)
            WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId}
              AND processing_lease_token=#{token}
            """)
    int finishIgnored(@Param("instanceId") String instanceId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("reason") String reason);

    @Update("""
            UPDATE ai_mobile_event SET processing_status='prefiltered', reason_code=#{reason},
                processing_lease_owner=NULL, processing_lease_token=NULL, processing_lease_until=NULL,
                processed_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3)
            WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId}
              AND processing_lease_token=#{token}
            """)
    int finishPrefiltered(@Param("instanceId") String instanceId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("reason") String reason);

    @Update("""
            UPDATE ai_mobile_event SET processing_status='classified', category=#{category},
                severity=#{severity}, confidence=#{confidence}, spoken_summary=#{spokenSummary},
                reason_code=#{reason}, processing_lease_owner=NULL, processing_lease_token=NULL,
                processing_lease_until=NULL, processed_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3)
            WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId}
              AND processing_lease_token=#{token}
            """)
    int finishClassified(@Param("instanceId") String instanceId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("category") String category,
            @Param("severity") String severity, @Param("confidence") double confidence,
            @Param("spokenSummary") String spokenSummary, @Param("reason") String reason);

    @Update("""
            UPDATE ai_mobile_event SET processing_status='converted', category=#{category},
                severity=#{severity}, confidence=#{confidence}, spoken_summary=#{spokenSummary},
                proactive_event_id=#{proactiveEventId}, reason_code='proactive_event_created',
                processing_lease_owner=NULL, processing_lease_token=NULL, processing_lease_until=NULL,
                processed_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3)
            WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId}
              AND processing_lease_token=#{token}
            """)
    int finishConverted(@Param("instanceId") String instanceId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("category") String category,
            @Param("severity") String severity, @Param("confidence") double confidence,
            @Param("spokenSummary") String spokenSummary,
            @Param("proactiveEventId") String proactiveEventId);

    @Update("""
            UPDATE ai_mobile_event SET processing_status='error', reason_code=#{reason},
                next_attempt_at=#{nextAttemptAt}, processing_lease_owner=NULL,
                processing_lease_token=NULL, processing_lease_until=NULL,
                processed_at=CURRENT_TIMESTAMP(3), updated_at=CURRENT_TIMESTAMP(3)
            WHERE mobile_instance_id=#{instanceId} AND event_id=#{eventId}
              AND processing_lease_token=#{token}
            """)
    int finishError(@Param("instanceId") String instanceId, @Param("eventId") String eventId,
            @Param("token") String token, @Param("reason") String reason,
            @Param("nextAttemptAt") java.util.Date nextAttemptAt);

    @Select("""
            <script>
            SELECT e.mobile_instance_id, mi.device_id, e.event_id, e.event_type,
                   e.source_package, e.event_state, e.summary, e.category, e.severity,
                   e.confidence, e.spoken_summary, e.reason_code, e.processing_status,
                   e.occurred_at, e.created_at, e.processed_at, e.proactive_event_id,
                   LOWER(CASE
                     WHEN p.expires_at IS NOT NULL AND p.expires_at &lt;= CURRENT_TIMESTAMP(3) THEN 'EXPIRED'
                     WHEN p.delivery_status IN ('DELIVERED','FAILED','DISMISSED') THEN p.delivery_status
                     WHEN COALESCE(dc.delivery_status,p.delivery_status)='CLAIMED'
                       AND (COALESCE(dc.claimed_at,p.claimed_at) IS NULL
                         OR COALESCE(dc.claimed_at,p.claimed_at) &lt; DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND))
                       THEN 'PENDING'
                     ELSE COALESCE(dc.delivery_status,p.delivery_status)
                   END) AS delivery_status
            FROM ai_mobile_event e
            INNER JOIN ai_mobile_instance mi ON mi.mobile_instance_id=e.mobile_instance_id
                AND mi.user_id=#{userId}
            LEFT JOIN ai_device_proactive_event p ON p.device_id=mi.device_id
                AND p.event_id=e.proactive_event_id
            LEFT JOIN ai_proactive_delivery_claim dc ON dc.user_id=mi.user_id
                AND dc.delivery_group_key=p.delivery_group_key
            WHERE e.mobile_instance_id=#{instanceId}
              <if test="type != null">AND e.event_type=#{type}</if>
              <if test="processingStatus != null">AND e.processing_status=#{processingStatus}</if>
              <if test="deliveryStatus != null">AND (CASE
                WHEN p.expires_at IS NOT NULL AND p.expires_at &lt;= CURRENT_TIMESTAMP(3) THEN 'EXPIRED'
                WHEN p.delivery_status IN ('DELIVERED','FAILED','DISMISSED') THEN p.delivery_status
                WHEN COALESCE(dc.delivery_status,p.delivery_status)='CLAIMED'
                  AND (COALESCE(dc.claimed_at,p.claimed_at) IS NULL
                    OR COALESCE(dc.claimed_at,p.claimed_at) &lt; DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND))
                  THEN 'PENDING'
                ELSE COALESCE(dc.delivery_status,p.delivery_status)
              END)=#{deliveryStatus}</if>
              <if test="fromTime != null">AND e.occurred_at &gt;= #{fromTime}</if>
              <if test="toTime != null">AND e.occurred_at &lt;= #{toTime}</if>
            ORDER BY e.occurred_at DESC, e.event_id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MobileEventAuditRow> pageAuditForUser(@Param("userId") Long userId,
            @Param("instanceId") String instanceId, @Param("type") String type,
            @Param("processingStatus") String processingStatus,
            @Param("deliveryStatus") String deliveryStatus,
            @Param("fromTime") java.util.Date fromTime, @Param("toTime") java.util.Date toTime,
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_mobile_event e
            INNER JOIN ai_mobile_instance mi ON mi.mobile_instance_id=e.mobile_instance_id
                AND mi.user_id=#{userId}
            LEFT JOIN ai_device_proactive_event p ON p.device_id=mi.device_id
                AND p.event_id=e.proactive_event_id
            LEFT JOIN ai_proactive_delivery_claim dc ON dc.user_id=mi.user_id
                AND dc.delivery_group_key=p.delivery_group_key
            WHERE e.mobile_instance_id=#{instanceId}
              <if test="type != null">AND e.event_type=#{type}</if>
              <if test="processingStatus != null">AND e.processing_status=#{processingStatus}</if>
              <if test="deliveryStatus != null">AND (CASE
                WHEN p.expires_at IS NOT NULL AND p.expires_at &lt;= CURRENT_TIMESTAMP(3) THEN 'EXPIRED'
                WHEN p.delivery_status IN ('DELIVERED','FAILED','DISMISSED') THEN p.delivery_status
                WHEN COALESCE(dc.delivery_status,p.delivery_status)='CLAIMED'
                  AND (COALESCE(dc.claimed_at,p.claimed_at) IS NULL
                    OR COALESCE(dc.claimed_at,p.claimed_at) &lt; DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND))
                  THEN 'PENDING'
                ELSE COALESCE(dc.delivery_status,p.delivery_status)
              END)=#{deliveryStatus}</if>
              <if test="fromTime != null">AND e.occurred_at &gt;= #{fromTime}</if>
              <if test="toTime != null">AND e.occurred_at &lt;= #{toTime}</if>
            </script>
            """)
    long countAuditForUser(@Param("userId") Long userId, @Param("instanceId") String instanceId,
            @Param("type") String type, @Param("processingStatus") String processingStatus,
            @Param("deliveryStatus") String deliveryStatus,
            @Param("fromTime") java.util.Date fromTime, @Param("toTime") java.util.Date toTime);
}
