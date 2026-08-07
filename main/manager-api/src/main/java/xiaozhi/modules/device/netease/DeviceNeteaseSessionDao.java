package xiaozhi.modules.device.netease;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.Date;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface DeviceNeteaseSessionDao extends BaseMapper<DeviceNeteaseSessionEntity> {
    @Update("""
            UPDATE ai_device_netease_session
            SET status = #{status}, updated_at = #{now}
            WHERE session_id = #{sessionId} AND device_id = #{deviceId}
              AND active_slot = #{deviceId}
              AND ((#{status} = 'WAITING_SCAN' AND status = 'WAITING_SCAN')
                   OR (#{status} = 'SCANNED' AND status IN ('WAITING_SCAN', 'SCANNED')))
              AND expires_at > #{now}
            """)
    int updateActiveStatus(@Param("sessionId") String sessionId, @Param("deviceId") String deviceId,
            @Param("status") String status, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_netease_session
            SET qr_key_ciphertext = #{qrKey}, qr_content = #{qrContent}, updated_at = #{now}
            WHERE session_id = #{sessionId} AND device_id = #{deviceId}
              AND active_slot = #{deviceId} AND status = 'WAITING_SCAN' AND expires_at > #{now}
            """)
    int setQrDataIfActive(@Param("sessionId") String sessionId, @Param("deviceId") String deviceId,
            @Param("qrKey") String qrKey, @Param("qrContent") String qrContent, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_netease_session
            SET status = #{status}, failure_reason = #{reason}, active_slot = NULL,
                qr_key_ciphertext = NULL, qr_content = NULL, updated_at = #{now}
            WHERE session_id = #{sessionId} AND device_id = #{deviceId}
              AND active_slot = #{deviceId} AND status IN ('WAITING_SCAN', 'SCANNED')
            """)
    int finishIfActive(@Param("sessionId") String sessionId, @Param("deviceId") String deviceId,
            @Param("status") String status, @Param("reason") String reason, @Param("now") Date now);

    @Update("""
            UPDATE ai_device_netease_session
            SET status = 'LOGGED_IN', failure_reason = NULL, active_slot = NULL,
                qr_key_ciphertext = NULL, qr_content = NULL, updated_at = #{now}
            WHERE session_id = #{sessionId} AND device_id = #{deviceId}
              AND active_slot = #{deviceId} AND status IN ('WAITING_SCAN', 'SCANNED')
              AND expires_at > #{now}
            """)
    int completeIfActive(@Param("sessionId") String sessionId, @Param("deviceId") String deviceId,
            @Param("now") Date now);

    @Update("""
            UPDATE ai_device_netease_session
            SET status = 'FAILED', failure_reason = #{reason}, active_slot = NULL,
                qr_key_ciphertext = NULL, qr_content = NULL, updated_at = #{now}
            WHERE device_id = #{deviceId} AND active_slot = #{deviceId}
              AND status IN ('WAITING_SCAN', 'SCANNED')
            """)
    int terminateAllActive(@Param("deviceId") String deviceId, @Param("reason") String reason,
            @Param("now") Date now);
}
