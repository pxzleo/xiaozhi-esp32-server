package xiaozhi.modules.device.proactive;

import java.util.Date;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ProactiveHabitDao extends BaseMapper<ProactiveHabitEntity> {
    @Insert("""
            INSERT INTO ai_device_proactive_habit
                (device_id, mac_address, habit_type, habit_key, evidence_count, first_seen_at,
                 last_seen_at, suggested, accepted, dismissed, payload, created_at, updated_at)
            VALUES (#{h.deviceId}, #{h.macAddress}, #{h.habitType}, #{h.habitKey}, #{h.evidenceCount},
                    #{h.firstSeenAt}, #{h.lastSeenAt}, IF(#{h.evidenceCount} >= 3, 1, 0), 0, 0,
                    CAST(#{h.payload} AS JSON), #{h.createdAt}, #{h.updatedAt})
            ON DUPLICATE KEY UPDATE
                evidence_count = evidence_count + VALUES(evidence_count),
                first_seen_at = LEAST(first_seen_at, VALUES(first_seen_at)),
                last_seen_at = GREATEST(last_seen_at, VALUES(last_seen_at)),
                suggested = IF(evidence_count >= 3, 1, suggested),
                payload = VALUES(payload), updated_at = VALUES(updated_at)
            """)
    int observeAtomic(@Param("h") ProactiveHabitEntity habit);
}
