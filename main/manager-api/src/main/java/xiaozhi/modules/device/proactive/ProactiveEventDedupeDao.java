package xiaozhi.modules.device.proactive;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProactiveEventDedupeDao {
    @Insert("""
            INSERT IGNORE INTO ai_device_proactive_event_dedupe
                (device_id, event_type, dedupe_hash, created_at, updated_at)
            VALUES (#{deviceId}, #{eventType}, #{dedupeHash}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    int insertIfAbsent(@Param("deviceId") String deviceId, @Param("eventType") String eventType,
            @Param("dedupeHash") String dedupeHash);

    @Select("""
            SELECT device_id, event_type, dedupe_hash, last_event_id, last_created_at
            FROM ai_device_proactive_event_dedupe
            WHERE device_id = #{deviceId} AND event_type = #{eventType} AND dedupe_hash = #{dedupeHash}
            FOR UPDATE
            """)
    ProactiveEventDedupeEntity selectForUpdate(@Param("deviceId") String deviceId,
            @Param("eventType") String eventType, @Param("dedupeHash") String dedupeHash);

    @Select("""
            SELECT last_event_id
            FROM ai_device_proactive_event_dedupe
            WHERE device_id = #{deviceId} AND event_type = #{eventType} AND dedupe_hash = #{dedupeHash}
              AND last_event_id IS NOT NULL
              AND last_created_at > DATE_SUB(CURRENT_TIMESTAMP, INTERVAL #{windowHours} HOUR)
            """)
    String selectRecentEventId(@Param("deviceId") String deviceId,
            @Param("eventType") String eventType, @Param("dedupeHash") String dedupeHash,
            @Param("windowHours") int windowHours);

    @Select("""
            SELECT last_created_at
            FROM ai_device_proactive_event_dedupe
            WHERE device_id = #{deviceId} AND event_type = #{eventType} AND dedupe_hash = #{dedupeHash}
            """)
    java.util.Date selectLastCreatedAt(@Param("deviceId") String deviceId,
            @Param("eventType") String eventType, @Param("dedupeHash") String dedupeHash);

    @Update("""
            UPDATE ai_device_proactive_event_dedupe
            SET last_event_id = #{eventId}, last_created_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE device_id = #{deviceId} AND event_type = #{eventType} AND dedupe_hash = #{dedupeHash}
            """)
    int markCreated(@Param("deviceId") String deviceId, @Param("eventType") String eventType,
            @Param("dedupeHash") String dedupeHash, @Param("eventId") String eventId);
}
