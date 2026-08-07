package xiaozhi.modules.device.netease;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface DeviceNeteaseAuthDao extends BaseMapper<DeviceNeteaseAuthEntity> {
    @Update("""
            UPDATE ai_device_netease_auth
            SET credential_ciphertext = NULL, status = 'REVOKED',
                revoke_reason = 'CREDENTIAL_INVALIDATED', revoked_at = NOW(), updated_at = NOW(),
                version = version + 1
            WHERE device_id = #{deviceId} AND version = #{credentialVersion} AND status = 'LOGGED_IN'
            """)
    int invalidateIfVersionMatches(@Param("deviceId") String deviceId,
            @Param("credentialVersion") Integer credentialVersion);
}
