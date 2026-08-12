package xiaozhi.modules.device.proactive;

import java.time.LocalTime;
import java.util.Date;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ProactivePreferenceDao extends BaseMapper<ProactivePreferenceEntity> {
    @Insert("""
            INSERT IGNORE INTO ai_device_proactive_preference
                (device_id, mac_address, mode, daily_limit, allowed_topics, blocked_topics,
                 version, created_at, updated_at)
            VALUES (#{deviceId}, #{macAddress}, 'AGGRESSIVE', 0, JSON_ARRAY(), JSON_ARRAY(), 0, #{now}, #{now})
            """)
    int insertDefault(@Param("deviceId") String deviceId, @Param("macAddress") String macAddress,
            @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_preference
            SET mode = COALESCE(previous_mode, 'AGGRESSIVE'),
                daily_limit = CASE COALESCE(previous_mode, 'AGGRESSIVE')
                    WHEN 'AGGRESSIVE' THEN 0
                    WHEN 'CONSERVATIVE' THEN 1
                    WHEN 'ACTIVE' THEN CASE
                        WHEN previous_daily_limit BETWEEN 1 AND 5 THEN previous_daily_limit ELSE 5 END
                    ELSE 0
                END,
                previous_mode = NULL, previous_daily_limit = NULL, silent_until = NULL,
                version = version + 1, updated_at = #{now}
            WHERE device_id = #{deviceId} AND mode = 'TODAY_SILENT' AND silent_until <= #{now}
            """)
    int restoreExpiredSilent(@Param("deviceId") String deviceId, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_preference
            SET daily_limit = 0, version = version + 1, updated_at = #{now}
            WHERE device_id = #{deviceId}
              AND mode = 'AGGRESSIVE'
              AND daily_limit = #{expectedDailyLimit}
              AND daily_limit BETWEEN 1 AND 5
              AND version = #{expectedVersion}
            """)
    int normalizeLegacyAggressiveLimit(@Param("deviceId") String deviceId,
            @Param("expectedDailyLimit") Integer expectedDailyLimit,
            @Param("expectedVersion") Integer expectedVersion, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_preference
            SET previous_daily_limit = 0, version = version + 1, updated_at = #{now}
            WHERE device_id = #{deviceId}
              AND mode = 'TODAY_SILENT'
              AND previous_mode = 'AGGRESSIVE'
              AND previous_daily_limit = #{expectedDailyLimit}
              AND previous_daily_limit BETWEEN 1 AND 5
              AND version = #{expectedVersion}
            """)
    int normalizeLegacyPreviousAggressiveLimit(@Param("deviceId") String deviceId,
            @Param("expectedDailyLimit") Integer expectedDailyLimit,
            @Param("expectedVersion") Integer expectedVersion, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_preference
            SET quiet_start = #{quietStart}, quiet_end = #{quietEnd},
                version = version + 1, updated_at = #{now}
            WHERE device_id = #{deviceId} AND version = #{expectedVersion}
            """)
    int updateQuietHoursCas(@Param("deviceId") String deviceId,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("quietStart") LocalTime quietStart, @Param("quietEnd") LocalTime quietEnd,
            @Param("now") Date now);

    @Select("""
            SELECT * FROM ai_device_proactive_preference
            WHERE device_id = #{deviceId}
            FOR UPDATE
            """)
    ProactivePreferenceEntity selectByIdForUpdate(@Param("deviceId") String deviceId);
}
