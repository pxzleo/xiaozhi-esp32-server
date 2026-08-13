package xiaozhi.modules.device.dao;

import java.util.Date;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.device.entity.DeviceEntity;

@Mapper
public interface DeviceDao extends BaseMapper<DeviceEntity> {
    @Select("SELECT * FROM ai_device WHERE id = #{identifier} OR mac_address = #{identifier} LIMIT 1 FOR UPDATE")
    DeviceEntity selectByIdentifierForUpdate(@Param("identifier") String identifier);

    @Select("SELECT * FROM ai_device WHERE id = #{deviceId} FOR UPDATE")
    DeviceEntity selectByIdForUpdate(@Param("deviceId") String deviceId);

    @Select("SELECT * FROM ai_device WHERE user_id = #{userId} FOR UPDATE")
    List<DeviceEntity> selectByUserIdForUpdate(@Param("userId") Long userId);

    @Select("SELECT d.* FROM ai_device d WHERE d.user_id=#{userId} AND ("
            + "NOT EXISTS (SELECT 1 FROM ai_mobile_instance any_mobile WHERE any_mobile.device_id=d.id) "
            + "OR EXISTS (SELECT 1 FROM ai_mobile_instance canonical_mobile WHERE canonical_mobile.device_id=d.id "
            + "AND canonical_mobile.mobile_instance_id=canonical_mobile.canonical_instance_id)) FOR UPDATE")
    List<DeviceEntity> selectRoutableByUserForUpdate(@Param("userId") Long userId);

    @Select("SELECT d.* FROM ai_device d WHERE d.user_id=#{userId} AND ("
            + "NOT EXISTS (SELECT 1 FROM ai_mobile_instance any_mobile WHERE any_mobile.device_id=d.id) "
            + "OR EXISTS (SELECT 1 FROM ai_mobile_instance canonical_mobile WHERE canonical_mobile.device_id=d.id "
            + "AND canonical_mobile.mobile_instance_id=canonical_mobile.canonical_instance_id)) ORDER BY d.sort,d.id")
    List<DeviceEntity> selectRoutableByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM ai_device WHERE agent_id = #{agentId} FOR UPDATE")
    List<DeviceEntity> selectByAgentIdForUpdate(@Param("agentId") String agentId);

    @Select("""
            SELECT d.* FROM ai_device d
            INNER JOIN ai_mobile_instance source
              ON source.mobile_instance_id=#{mobileInstanceId} AND source.user_id=#{userId}
            WHERE d.user_id=#{userId} AND d.agent_id=#{agentId} AND (
              NOT EXISTS (SELECT 1 FROM ai_mobile_instance target_mobile WHERE target_mobile.device_id=d.id)
              OR EXISTS (SELECT 1 FROM ai_mobile_instance canonical_mobile
                WHERE canonical_mobile.device_id=d.id
                  AND canonical_mobile.mobile_instance_id=canonical_mobile.canonical_instance_id))
            FOR UPDATE
            """)
    List<DeviceEntity> selectMobileAlertTargetsForUpdate(@Param("userId") Long userId,
            @Param("agentId") String agentId, @Param("mobileInstanceId") String mobileInstanceId);

    @Select("""
            SELECT d.id, d.user_id, d.mac_address,
                   CASE WHEN mi.mobile_instance_id IS NULL THEN d.last_connected_at ELSE COALESCE((
                     SELECT MAX(COALESCE(member.last_connected_at, member_device.last_connected_at))
                     FROM ai_mobile_instance member
                     INNER JOIN ai_device member_device ON member_device.id=member.device_id
                     WHERE member.canonical_instance_id=mi.mobile_instance_id
                   ),d.last_connected_at) END AS last_connected_at,
                   d.auto_update, d.board, d.alias, d.display_name, d.agent_id,
                   CASE WHEN mi.mobile_instance_id IS NULL THEN d.app_version ELSE COALESCE((
                     SELECT member.app_version FROM ai_mobile_instance member
                     WHERE member.canonical_instance_id=mi.mobile_instance_id
                     ORDER BY COALESCE(member.last_connected_at,member.updated_at) DESC LIMIT 1
                   ),d.app_version) END AS app_version,
                   d.sort, d.updater, d.update_date, d.creator, d.create_date
            FROM ai_device d
            LEFT JOIN ai_mobile_instance mi ON mi.device_id=d.id
            WHERE d.user_id=#{userId} AND d.agent_id=#{agentId}
              AND (mi.mobile_instance_id IS NULL
                   OR mi.canonical_instance_id=mi.mobile_instance_id)
            """)
    List<DeviceEntity> selectVisibleByUserAndAgent(@Param("userId") Long userId,
            @Param("agentId") String agentId);

    @Select("SELECT alias.device_id FROM ai_mobile_instance selected "
            + "INNER JOIN ai_mobile_instance alias "
            + "ON alias.canonical_instance_id=selected.canonical_instance_id "
            + "WHERE selected.user_id=#{userId} AND selected.device_id=#{deviceId}")
    List<String> selectMobileGroupDeviceIds(@Param("userId") Long userId,
            @Param("deviceId") String deviceId);

    /**
     * 获取此智能体全部设备的最后连接时间
     * 
     * @param agentId 智能体id
     * @return
     */
    Date getAllLastConnectedAtByAgentId(String agentId);

}
