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
            INSERT INTO ai_device_proactive_event
                (device_id, mac_address, event_id, topic, priority, reason, event_type, payload,
                 created_at, expires_at, dedupe_key, requires_response, delivery_status, outcome, updated_at)
            VALUES (#{e.deviceId}, #{e.macAddress}, #{e.eventId}, #{e.topic}, #{e.priority}, #{e.reason},
                    #{e.eventType}, CAST(#{e.payload} AS JSON), #{e.createdAt}, #{e.expiresAt}, #{e.dedupeKey},
                    #{e.requiresResponse}, #{e.deliveryStatus}, #{e.outcome}, #{e.updatedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIfAbsent(@Param("e") ProactiveEventEntity event);

    @Update("""
            UPDATE ai_device_proactive_event
            SET delivery_status = #{status}, outcome = #{outcome},
                delivered_at = CASE WHEN #{status} = 'DELIVERED' AND delivered_at IS NULL THEN #{now} ELSE delivered_at END,
                updated_at = #{now}
            WHERE device_id = #{deviceId} AND event_id = #{eventId}
              AND delivery_status = #{expectedStatus}
              AND (#{expectedStatus} <> 'CLAIMED' OR claim_token = #{claimToken})
            """)
    int updateStatusCas(@Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("expectedStatus") String expectedStatus, @Param("claimToken") String claimToken,
            @Param("status") String status, @Param("outcome") String outcome, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_event
            SET claimed_at = CASE
                    WHEN claim_token = #{claimToken} AND claimed_at >= #{leaseCutoff} THEN claimed_at
                    ELSE #{now} END,
                delivery_status = 'CLAIMED', claim_token = #{claimToken},
                updated_at = #{now}
            WHERE device_id = #{deviceId} AND event_id = #{eventId}
              AND (expires_at IS NULL OR expires_at > #{now})
              AND (delivery_status = 'PENDING'
                   OR (delivery_status = 'CLAIMED' AND claim_token = #{claimToken})
                   OR (delivery_status = 'CLAIMED'
                       AND (claimed_at IS NULL OR claimed_at < #{leaseCutoff})))
            """)
    int claimPending(@Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("claimToken") String claimToken, @Param("now") Date now,
            @Param("leaseCutoff") Date leaseCutoff);

    @Select("""
            <script>
            SELECT e.* FROM ai_device_proactive_event e
            INNER JOIN ai_device d ON d.id = e.device_id AND d.user_id = #{userId}
            <where>
              <if test="deviceId != null">AND e.device_id = #{deviceId}</if>
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
              <if test="deviceId != null">AND e.device_id = #{deviceId}</if>
              <if test="topic != null">AND e.topic = #{topic}</if>
              <if test="status != null">AND e.delivery_status = #{status}</if>
              <if test="eventType != null">AND e.event_type = #{eventType}</if>
            </where>
            </script>
            """)
    long countForUser(@Param("userId") Long userId, @Param("deviceId") String deviceId,
            @Param("topic") String topic, @Param("status") String status,
            @Param("eventType") String eventType);
}
