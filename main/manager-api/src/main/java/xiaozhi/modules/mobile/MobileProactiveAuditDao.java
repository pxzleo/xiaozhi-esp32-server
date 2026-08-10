package xiaozhi.modules.mobile;

import java.util.Date;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MobileProactiveAuditDao {
    @Update("""
            UPDATE ai_device_proactive_event
            SET mobile_terminal_status = #{status}, mobile_terminal_reason = #{reason}, updated_at = #{now}
            WHERE device_id = #{deviceId} AND event_id = #{eventId} AND claim_token = #{claimToken}
              AND delivery_status IN ('DELIVERED', 'FAILED')
            """)
    int recordTerminal(@Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("claimToken") String claimToken, @Param("status") String status,
            @Param("reason") String reason, @Param("now") Date now);
}
