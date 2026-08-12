package xiaozhi.modules.device.proactive;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ProactiveEventDao extends BaseMapper<ProactiveEventEntity> {
    @Update("""
            UPDATE ai_device_proactive_event
            SET delivery_status = 'PENDING', claim_token = NULL, claimed_at = NULL,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE device_id = #{deviceId}
              AND event_type IN ('WEATHER_ALERT', 'NEWS_ALERT', 'MOBILE_ALERT')
              AND delivery_status = 'CLAIMED'
              AND (claimed_at IS NULL OR claimed_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND))
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))
            """)
    int releaseExpiredMonitorClaims(@Param("deviceId") String deviceId);

    @Select("""
            SELECT * FROM ai_device_proactive_event
            WHERE device_id = #{deviceId} AND event_id = #{eventId}
            """)
    ProactiveEventEntity selectByDeviceAndEventId(@Param("deviceId") String deviceId,
            @Param("eventId") String eventId);

    @Select("""
            SELECT * FROM ai_device_proactive_event
            WHERE device_id = #{deviceId} AND event_id = #{eventId}
            FOR UPDATE
            """)
    ProactiveEventEntity selectByDeviceAndEventIdForUpdate(@Param("deviceId") String deviceId,
            @Param("eventId") String eventId);

    @Insert("""
            INSERT IGNORE INTO ai_device_proactive_event
                (device_id, mac_address, event_id, topic, priority, reason, event_type, payload,
                 created_at, expires_at, dedupe_key, delivery_group_key, delivery_group_window_hours,
                 requires_response, delivery_status, outcome, updated_at)
            VALUES (#{e.deviceId}, #{e.macAddress}, #{e.eventId}, #{e.topic}, #{e.priority}, #{e.reason},
                    #{e.eventType}, CAST(#{e.payload} AS JSON), #{e.createdAt}, #{e.expiresAt}, #{e.dedupeKey},
                    #{e.deliveryGroupKey}, #{e.deliveryGroupWindowHours}, #{e.requiresResponse},
                    #{e.deliveryStatus}, #{e.outcome}, #{e.updatedAt})
            """)
    int insertIfAbsent(@Param("e") ProactiveEventEntity event);

    @Update("""
            UPDATE ai_device_proactive_event
            SET delivery_status = #{status}, outcome = #{outcome},
                delivered_at = CASE WHEN #{status} = 'DELIVERED' AND delivered_at IS NULL THEN #{now} ELSE delivered_at END,
                updated_at = #{now}
            WHERE device_id = #{deviceId} AND event_id = #{eventId}
              AND delivery_status = #{expectedStatus}
              AND (claim_token IS NULL OR (
                  claim_token = #{claimToken}
                  AND claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))))
            """)
    int updateStatusCas(@Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("expectedStatus") String expectedStatus, @Param("claimToken") String claimToken,
            @Param("status") String status, @Param("outcome") String outcome, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_event e
            LEFT JOIN ai_device_proactive_monitor m
              ON m.device_id = e.device_id
             AND m.monitor_type = CASE e.event_type
                    WHEN 'WEATHER_ALERT' THEN 'WEATHER'
                    WHEN 'NEWS_ALERT' THEN 'NEWS'
                 END
            LEFT JOIN sys_params g
              ON g.param_code = 'proactive.external_monitoring_enabled'
            SET e.claimed_at = CASE
                    WHEN e.claim_token = #{claimToken}
                         AND e.claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)
                    THEN e.claimed_at
                    ELSE CURRENT_TIMESTAMP(3) END,
                e.delivery_status = 'CLAIMED', e.claim_token = #{claimToken},
                e.updated_at = CURRENT_TIMESTAMP(3)
            WHERE e.device_id = #{deviceId} AND e.event_id = #{eventId}
              AND (e.expires_at IS NULL OR e.expires_at > CURRENT_TIMESTAMP(3))
              AND (e.event_type NOT IN ('WEATHER_ALERT', 'NEWS_ALERT')
                   OR (m.enabled = 1 AND LOWER(TRIM(g.param_value)) = 'true'))
              AND (e.delivery_status = 'PENDING'
                   OR (e.delivery_status = 'CLAIMED' AND e.claim_token = #{claimToken})
                   OR (e.delivery_status = 'CLAIMED'
                       AND (e.claimed_at IS NULL OR e.claimed_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND))))
            """)
    int claimPending(@Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("claimToken") String claimToken);

    @Update("""
            UPDATE ai_device_proactive_event
            SET delivery_status = 'PENDING', claim_token = NULL, claimed_at = NULL,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE device_id = #{deviceId} AND event_id = #{eventId}
              AND delivery_status = 'CLAIMED' AND claim_token = #{claimToken}
            """)
    int releaseClaim(@Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("claimToken") String claimToken);

    @Select("""
            <script>
            SELECT e.* FROM ai_device_proactive_event e
            INNER JOIN ai_device d ON d.id = e.device_id AND d.user_id = #{userId}
            <where>
              <if test="deviceId != null">AND (e.device_id = #{deviceId} OR e.device_id IN (
                SELECT alias.device_id FROM ai_mobile_instance alias
                INNER JOIN ai_mobile_instance canonical
                  ON canonical.mobile_instance_id=alias.canonical_instance_id
                WHERE canonical.device_id=#{deviceId}
              ))</if>
              <if test="topic != null">AND e.topic = #{topic}</if>
              <if test="status != null">AND e.delivery_status = #{status}</if>
              <if test="eventType != null">AND e.event_type = #{eventType}</if>
            </where>
            ORDER BY e.created_at DESC, e.id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ProactiveEventEntity> pageForUser(@Param("userId") Long userId,
            @Param("deviceId") String deviceId, @Param("topic") String topic,
            @Param("status") String status, @Param("eventType") String eventType,
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_device_proactive_event e
            INNER JOIN ai_device d ON d.id = e.device_id AND d.user_id = #{userId}
            <where>
              <if test="deviceId != null">AND (e.device_id = #{deviceId} OR e.device_id IN (
                SELECT alias.device_id FROM ai_mobile_instance alias
                INNER JOIN ai_mobile_instance canonical
                  ON canonical.mobile_instance_id=alias.canonical_instance_id
                WHERE canonical.device_id=#{deviceId}
              ))</if>
              <if test="topic != null">AND e.topic = #{topic}</if>
              <if test="status != null">AND e.delivery_status = #{status}</if>
              <if test="eventType != null">AND e.event_type = #{eventType}</if>
            </where>
            </script>
            """)
    long countForUser(@Param("userId") Long userId, @Param("deviceId") String deviceId,
            @Param("topic") String topic, @Param("status") String status,
            @Param("eventType") String eventType);

    @Select("""
            SELECT e.* FROM ai_device_proactive_event e
            LEFT JOIN ai_device_proactive_monitor m
              ON m.device_id = e.device_id
             AND m.enabled = 1
             AND m.monitor_type = CASE e.event_type
                    WHEN 'WEATHER_ALERT' THEN 'WEATHER'
                    WHEN 'NEWS_ALERT' THEN 'NEWS'
                 END
            LEFT JOIN sys_params g
              ON g.param_code = 'proactive.external_monitoring_enabled'
             AND LOWER(TRIM(g.param_value)) = 'true'
            INNER JOIN ai_device d ON d.id = e.device_id
            LEFT JOIN ai_proactive_delivery_claim dc
              ON dc.user_id = d.user_id AND dc.delivery_group_key = e.delivery_group_key
            WHERE e.device_id = #{deviceId}
              AND e.event_type IN ('WEATHER_ALERT', 'NEWS_ALERT', 'MOBILE_ALERT')
              AND (e.expires_at IS NULL OR e.expires_at > CURRENT_TIMESTAMP(3))
              AND e.delivery_status = 'PENDING'
              AND (e.event_type = 'MOBILE_ALERT'
                   OR (m.enabled = 1 AND LOWER(TRIM(g.param_value)) = 'true'))
              AND (dc.user_id IS NULL OR dc.delivery_status = 'FAILED'
                   OR (dc.delivery_status = 'CLAIMED'
                       AND (dc.claimed_at IS NULL OR dc.claimed_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)))
                   OR (dc.delivery_status = 'DELIVERED' AND e.delivery_group_window_hours > 0
                       AND dc.event_created_at IS NOT NULL
                       AND e.created_at >= DATE_ADD(dc.event_created_at,
                           INTERVAL e.delivery_group_window_hours HOUR)))
            ORDER BY CASE e.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                     WHEN 'NORMAL' THEN 2 ELSE 3 END,
                     e.created_at, e.id
            LIMIT 20
            """)
    List<ProactiveEventEntity> selectPendingMonitorEvents(@Param("deviceId") String deviceId);

    @Select("""
            SELECT e.* FROM ai_device_proactive_event e
            LEFT JOIN ai_device_proactive_monitor m
              ON m.device_id = e.device_id
             AND m.enabled = 1
             AND m.monitor_type = CASE e.event_type
                    WHEN 'WEATHER_ALERT' THEN 'WEATHER'
                    WHEN 'NEWS_ALERT' THEN 'NEWS'
                 END
            LEFT JOIN sys_params g
              ON g.param_code = 'proactive.external_monitoring_enabled'
             AND LOWER(TRIM(g.param_value)) = 'true'
            WHERE e.mac_address = #{macAddress} AND e.event_id = #{eventId}
              AND e.event_type IN ('WEATHER_ALERT', 'NEWS_ALERT', 'MOBILE_ALERT')
              AND e.delivery_status IN ('PENDING','CLAIMED')
              AND (e.expires_at IS NULL OR e.expires_at > CURRENT_TIMESTAMP(3))
              AND (e.event_type = 'MOBILE_ALERT'
                   OR (m.enabled = 1 AND LOWER(TRIM(g.param_value)) = 'true'))
            """)
    ProactiveEventEntity selectMonitorEventByMacAndEventId(@Param("macAddress") String macAddress,
            @Param("eventId") String eventId);

    @Select("""
            SELECT e.* FROM ai_device_proactive_event e
            WHERE e.mac_address = #{macAddress} AND e.event_id = #{eventId}
              AND e.event_type IN ('WEATHER_ALERT', 'NEWS_ALERT', 'MOBILE_ALERT')
              AND e.claim_token = #{claimToken}
              AND ((e.delivery_status = 'CLAIMED'
                    AND e.claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND))
                   OR e.delivery_status = 'DELIVERED')
              AND (e.expires_at IS NULL OR e.expires_at > CURRENT_TIMESTAMP(3))
            """)
    ProactiveEventEntity selectClaimedMonitorEvent(@Param("macAddress") String macAddress,
            @Param("eventId") String eventId, @Param("claimToken") String claimToken,
            @Param("now") Date now);
}
