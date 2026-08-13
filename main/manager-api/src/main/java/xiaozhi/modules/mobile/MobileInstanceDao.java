package xiaozhi.modules.mobile;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface MobileInstanceDao extends BaseMapper<MobileInstanceEntity> {
    @Select("SELECT * FROM ai_mobile_instance WHERE user_id = #{userId} AND installation_id = #{installationId} LIMIT 1 FOR UPDATE")
    MobileInstanceEntity selectActiveForUpdate(@Param("userId") Long userId,
            @Param("installationId") String installationId);

    @Select("SELECT * FROM ai_mobile_instance WHERE user_id=#{userId} AND stable_device_key=#{stableKey} LIMIT 1 FOR UPDATE")
    MobileInstanceEntity selectByStableKeyForUpdate(@Param("userId") Long userId,
            @Param("stableKey") String stableKey);

    @Select("SELECT * FROM ai_mobile_instance WHERE user_id=#{userId} AND device_id=#{deviceId} LIMIT 1 FOR UPDATE")
    MobileInstanceEntity selectByDeviceForUpdate(@Param("userId") Long userId,
            @Param("deviceId") String deviceId);

    @Select("SELECT * FROM ai_mobile_instance WHERE mobile_instance_id = #{instanceId} FOR UPDATE")
    MobileInstanceEntity selectByIdForUpdate(@Param("instanceId") String instanceId);

    @Select("SELECT canonical.* FROM ai_mobile_instance source "
            + "INNER JOIN ai_mobile_instance canonical ON canonical.mobile_instance_id=source.canonical_instance_id "
            + "WHERE source.mobile_instance_id=#{instanceId} FOR UPDATE")
    MobileInstanceEntity selectCanonicalByInstanceForUpdate(@Param("instanceId") String instanceId);

    @Select("SELECT canonical.* FROM ai_mobile_instance source "
            + "INNER JOIN ai_mobile_instance canonical ON canonical.mobile_instance_id=source.canonical_instance_id "
            + "WHERE source.mobile_instance_id=#{instanceId}")
    MobileInstanceEntity selectCanonicalByInstance(@Param("instanceId") String instanceId);

    @Select("SELECT * FROM ai_mobile_instance WHERE user_id=#{userId} "
            + "AND mobile_instance_id=canonical_instance_id ORDER BY created_at,mobile_instance_id")
    List<MobileInstanceEntity> selectCanonicalByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM ai_mobile_instance WHERE user_id=#{userId} "
            + "AND canonical_instance_id=#{canonicalId} FOR UPDATE")
    List<MobileInstanceEntity> selectCanonicalGroupForUpdate(@Param("userId") Long userId,
            @Param("canonicalId") String canonicalId);

    @Insert("INSERT IGNORE INTO ai_device (id, user_id, mac_address, auto_update, board, alias, display_name, agent_id, app_version, creator, create_date, updater, update_date) VALUES (#{deviceId}, #{userId}, #{mobileInstanceId}, 0, 'android-mobile', 'Android 小智', CONCAT('手机 · ',RIGHT(#{mobileInstanceId},4)), #{agentId}, #{appVersion}, #{userId}, NOW(), #{userId}, NOW())")
    int insertDeviceIgnore(MobileInstanceEntity entity);

    @Insert("INSERT IGNORE INTO ai_mobile_instance (mobile_instance_id, device_id, user_id, installation_id, stable_device_key, canonical_instance_id, agent_id, platform, app_version, capabilities, credential_hash, credential_version, created_at, updated_at) VALUES (#{mobileInstanceId}, #{deviceId}, #{userId}, #{installationId}, #{stableDeviceKey}, #{canonicalInstanceId}, #{agentId}, #{platform}, #{appVersion}, #{capabilities}, #{credentialHash}, #{credentialVersion}, #{createdAt}, #{updatedAt})")
    int insertIgnore(MobileInstanceEntity entity);

    @Update("UPDATE ai_mobile_instance SET alert_sensitivity=#{sensitivity}, "
            + "alert_categories=#{categories}, updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE mobile_instance_id=#{instanceId} AND user_id=#{userId} AND revoked_at IS NULL")
    int updateAlertSettings(@Param("userId") Long userId, @Param("instanceId") String instanceId,
            @Param("sensitivity") String sensitivity, @Param("categories") String categories);

    @Update("UPDATE ai_mobile_instance SET canonical_instance_id=#{canonicalId}, updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE user_id=#{userId} AND canonical_instance_id=#{sourceCanonicalId}")
    int mergeCanonicalGroup(@Param("userId") Long userId,
            @Param("sourceCanonicalId") String sourceCanonicalId,
            @Param("canonicalId") String canonicalId);

    @Update("UPDATE ai_mobile_instance SET stable_device_key=NULL, updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE user_id=#{userId} AND canonical_instance_id=#{canonicalId}")
    int clearStableKeysInGroup(@Param("userId") Long userId,
            @Param("canonicalId") String canonicalId);

    @Update("UPDATE ai_mobile_instance SET stable_device_key=#{stableKey}, updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE user_id=#{userId} AND mobile_instance_id=#{instanceId}")
    int setStableDeviceKey(@Param("userId") Long userId, @Param("instanceId") String instanceId,
            @Param("stableKey") String stableKey);

    @Update("UPDATE ai_mobile_instance SET installation_id=CONCAT(SUBSTR(LOWER(UUID()),1,24), "
            + "LPAD(SUBSTR(MD5(mobile_instance_id),1,12),12,'0')), revoked_at=CURRENT_TIMESTAMP(3), "
            + "credential_hash=NULL, credential_version=credential_version+1, "
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=#{userId} "
            + "AND canonical_instance_id=#{canonicalId} AND mobile_instance_id != #{canonicalId}")
    int retireMergedAliases(@Param("userId") Long userId,
            @Param("canonicalId") String canonicalId);

}
