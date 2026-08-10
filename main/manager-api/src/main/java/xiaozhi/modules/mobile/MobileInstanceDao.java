package xiaozhi.modules.mobile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface MobileInstanceDao extends BaseMapper<MobileInstanceEntity> {
    @Select("SELECT * FROM ai_mobile_instance WHERE user_id = #{userId} AND installation_id = #{installationId} LIMIT 1 FOR UPDATE")
    MobileInstanceEntity selectActiveForUpdate(@Param("userId") Long userId,
            @Param("installationId") String installationId);

    @Select("SELECT * FROM ai_mobile_instance WHERE mobile_instance_id = #{instanceId} FOR UPDATE")
    MobileInstanceEntity selectByIdForUpdate(@Param("instanceId") String instanceId);

    @Insert("INSERT IGNORE INTO ai_device (id, user_id, mac_address, auto_update, board, alias, agent_id, app_version, creator, create_date, updater, update_date) VALUES (#{deviceId}, #{userId}, #{mobileInstanceId}, 0, 'android-mobile', 'Android 小智', #{agentId}, #{appVersion}, #{userId}, NOW(), #{userId}, NOW())")
    int insertDeviceIgnore(MobileInstanceEntity entity);

    @Insert("INSERT IGNORE INTO ai_mobile_instance (mobile_instance_id, device_id, user_id, installation_id, agent_id, platform, app_version, capabilities, credential_hash, credential_version, created_at, updated_at) VALUES (#{mobileInstanceId}, #{deviceId}, #{userId}, #{installationId}, #{agentId}, #{platform}, #{appVersion}, #{capabilities}, #{credentialHash}, #{credentialVersion}, #{createdAt}, #{updatedAt})")
    int insertIgnore(MobileInstanceEntity entity);
}
