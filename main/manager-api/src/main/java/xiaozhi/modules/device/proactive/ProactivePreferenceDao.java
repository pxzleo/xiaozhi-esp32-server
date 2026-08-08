package xiaozhi.modules.device.proactive;

import java.util.Date;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ProactivePreferenceDao extends BaseMapper<ProactivePreferenceEntity> {
    @Insert("""
            INSERT IGNORE INTO ai_device_proactive_preference
                (device_id, mac_address, mode, daily_limit, allowed_topics, blocked_topics,
                 version, created_at, updated_at)
            VALUES (#{deviceId}, #{macAddress}, 'AGGRESSIVE', 5, JSON_ARRAY(), JSON_ARRAY(), 0, #{now}, #{now})
            """)
    int insertDefault(@Param("deviceId") String deviceId, @Param("macAddress") String macAddress,
            @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_preference
            SET mode = COALESCE(previous_mode, 'AGGRESSIVE'),
                daily_limit = COALESCE(previous_daily_limit, 5),
                previous_mode = NULL, previous_daily_limit = NULL, silent_until = NULL,
                version = version + 1, updated_at = #{now}
            WHERE device_id = #{deviceId} AND mode = 'TODAY_SILENT' AND silent_until <= #{now}
            """)
    int restoreExpiredSilent(@Param("deviceId") String deviceId, @Param("now") Date now);
}
