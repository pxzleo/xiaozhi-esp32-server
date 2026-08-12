package xiaozhi.modules.device.proactive;

import java.util.Date;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProactiveDeliveryClaimDao {
    @Insert("""
            INSERT IGNORE INTO ai_proactive_delivery_claim
                (user_id, delivery_group_key, delivery_status, updated_at)
            VALUES (#{userId}, #{groupKey}, 'PENDING', CURRENT_TIMESTAMP(3))
            """)
    int insertIfAbsent(@Param("userId") Long userId, @Param("groupKey") String groupKey);

    @Update("""
            UPDATE ai_proactive_delivery_claim
            SET claimed_at = CASE
                    WHEN claim_token = #{claimToken}
                         AND claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)
                    THEN claimed_at
                    ELSE CURRENT_TIMESTAMP(3) END,
                delivery_status = 'CLAIMED', claim_token = #{claimToken},
                device_id = #{deviceId}, event_id = #{eventId}, event_created_at = #{eventCreatedAt},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = #{userId} AND delivery_group_key = #{groupKey}
              AND (delivery_status IN ('PENDING', 'FAILED')
                   OR (delivery_status = 'CLAIMED' AND claim_token = #{claimToken})
                   OR (delivery_status = 'CLAIMED'
                       AND (claimed_at IS NULL OR claimed_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)))
                   OR (delivery_status = 'DELIVERED' AND #{windowHours} > 0
                       AND event_created_at IS NOT NULL
                       AND #{eventCreatedAt} >= DATE_ADD(event_created_at,
                           INTERVAL #{windowHours} HOUR)))
            """)
    int claim(@Param("userId") Long userId, @Param("groupKey") String groupKey,
            @Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("claimToken") String claimToken,
            @Param("eventCreatedAt") Date eventCreatedAt,
            @Param("windowHours") int windowHours);

    @Update("""
            UPDATE ai_proactive_delivery_claim
            SET delivery_status = #{status}, updated_at = #{now}
            WHERE user_id = #{userId} AND delivery_group_key = #{groupKey}
              AND delivery_status = 'CLAIMED' AND claim_token = #{claimToken}
              AND claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)
            """)
    int complete(@Param("userId") Long userId, @Param("groupKey") String groupKey,
            @Param("claimToken") String claimToken, @Param("status") String status,
            @Param("now") Date now);

    @Update("""
            UPDATE ai_proactive_delivery_claim
            SET delivery_status='DELIVERED', claim_token=NULL, claimed_at=NULL,
                device_id=#{deviceId}, event_id=#{eventId}, event_created_at=#{eventCreatedAt},
                updated_at=CURRENT_TIMESTAMP(3)
            WHERE user_id=#{userId} AND delivery_group_key=#{groupKey}
              AND (delivery_status IN ('PENDING','FAILED')
                   OR (delivery_status='DELIVERED' AND #{windowHours} > 0
                       AND event_created_at IS NOT NULL
                       AND #{eventCreatedAt} >= DATE_ADD(event_created_at,
                           INTERVAL #{windowHours} HOUR)))
            """)
    int completeUnclaimed(@Param("userId") Long userId, @Param("groupKey") String groupKey,
            @Param("deviceId") String deviceId, @Param("eventId") String eventId,
            @Param("eventCreatedAt") Date eventCreatedAt, @Param("windowHours") int windowHours);
}
