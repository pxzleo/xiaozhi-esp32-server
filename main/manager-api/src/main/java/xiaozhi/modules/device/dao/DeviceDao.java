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

    @Select("SELECT * FROM ai_device WHERE agent_id = #{agentId} FOR UPDATE")
    List<DeviceEntity> selectByAgentIdForUpdate(@Param("agentId") String agentId);

    /**
     * 获取此智能体全部设备的最后连接时间
     * 
     * @param agentId 智能体id
     * @return
     */
    Date getAllLastConnectedAtByAgentId(String agentId);

}
