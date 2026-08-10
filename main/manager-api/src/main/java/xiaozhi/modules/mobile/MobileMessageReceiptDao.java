package xiaozhi.modules.mobile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface MobileMessageReceiptDao extends BaseMapper<MobileMessageReceiptEntity> {
    @Insert("INSERT INTO ai_mobile_message_receipt (mobile_instance_id, message_id, status, claim_token, lease_expires_at, created_at, updated_at) VALUES (#{instanceId}, #{messageId}, 'PROCESSING', #{claimToken}, DATE_ADD(NOW(), INTERVAL 30 SECOND), NOW(), NOW())")
    int insertClaim(@Param("instanceId") String instanceId, @Param("messageId") String messageId,
            @Param("claimToken") String claimToken);

    @Delete("DELETE FROM ai_mobile_message_receipt WHERE mobile_instance_id = #{instanceId} AND created_at < DATE_SUB(NOW(), INTERVAL 30 DAY)")
    int deleteExpired(@Param("instanceId") String instanceId);

    @Select("SELECT * FROM ai_mobile_message_receipt WHERE mobile_instance_id = #{instanceId} AND message_id = #{messageId} FOR UPDATE")
    MobileMessageReceiptEntity selectForUpdate(@Param("instanceId") String instanceId,
            @Param("messageId") String messageId);

    @Update("UPDATE ai_mobile_message_receipt SET status = 'ACCEPTED', lease_expires_at = NULL, updated_at = NOW() WHERE mobile_instance_id = #{instanceId} AND message_id = #{messageId} AND status = 'PROCESSING' AND claim_token = #{claimToken}")
    int complete(@Param("instanceId") String instanceId, @Param("messageId") String messageId,
            @Param("claimToken") String claimToken);

    @Update("UPDATE ai_mobile_message_receipt SET lease_expires_at = DATE_ADD(NOW(), INTERVAL 30 SECOND), updated_at = NOW() WHERE mobile_instance_id = #{instanceId} AND message_id = #{messageId} AND status = 'PROCESSING' AND claim_token = #{claimToken}")
    int renew(@Param("instanceId") String instanceId, @Param("messageId") String messageId,
            @Param("claimToken") String claimToken);

    @Update("UPDATE ai_mobile_message_receipt SET status = 'PROCESSING', claim_token = #{claimToken}, lease_expires_at = DATE_ADD(NOW(), INTERVAL 30 SECOND), updated_at = NOW() WHERE mobile_instance_id = #{instanceId} AND message_id = #{messageId} AND status = 'PROCESSING' AND (lease_expires_at IS NULL OR lease_expires_at <= NOW())")
    int reclaim(@Param("instanceId") String instanceId, @Param("messageId") String messageId,
            @Param("claimToken") String claimToken);
}
