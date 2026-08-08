package xiaozhi.modules.device.proactive;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProactiveMonitorDao {
    @Insert("""
            INSERT IGNORE INTO ai_device_proactive_monitor
                (device_id, mac_address, monitor_type, enabled, interval_minutes, config, state,
                 next_check_at, version, created_at, updated_at)
            VALUES
                (#{deviceId}, #{macAddress}, 'WEATHER', 1, 30, CAST(#{weatherConfig} AS JSON),
                 JSON_OBJECT(), #{now}, 0, #{now}, #{now}),
                (#{deviceId}, #{macAddress}, 'NEWS', 1, 10, CAST(#{newsConfig} AS JSON),
                 JSON_OBJECT(), #{now}, 0, #{now}, #{now})
            """)
    int insertDefaults(@Param("deviceId") String deviceId, @Param("macAddress") String macAddress,
            @Param("weatherConfig") String weatherConfig, @Param("newsConfig") String newsConfig,
            @Param("now") Date now);

    @Select("""
            SELECT * FROM ai_device_proactive_monitor
            WHERE device_id = #{deviceId}
            ORDER BY monitor_type
            """)
    List<ProactiveMonitorEntity> selectByDevice(@Param("deviceId") String deviceId);

    @Select("""
            SELECT * FROM ai_device_proactive_monitor
            WHERE device_id = #{deviceId} AND monitor_type = #{monitorType}
            FOR UPDATE
            """)
    ProactiveMonitorEntity selectForUpdate(@Param("deviceId") String deviceId,
            @Param("monitorType") String monitorType);

    @Update("""
            UPDATE ai_device_proactive_monitor
            SET enabled = #{enabled}, interval_minutes = #{intervalMinutes},
                config = CAST(#{config} AS JSON), version = version + 1, updated_at = #{now},
                next_check_at = CASE WHEN #{enabled} = 1 THEN LEAST(next_check_at, #{now}) ELSE next_check_at END,
                lease_owner = NULL, lease_token = NULL, lease_until = NULL
            WHERE device_id = #{deviceId} AND monitor_type = #{monitorType}
            """)
    int updateConfiguration(@Param("deviceId") String deviceId,
            @Param("monitorType") String monitorType, @Param("enabled") boolean enabled,
            @Param("intervalMinutes") int intervalMinutes, @Param("config") String config,
            @Param("now") Date now);

    @Update("""
            UPDATE ai_device_proactive_monitor
            SET state = CASE
                    WHEN last_probe_at IS NULL
                      OR last_probe_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
                    THEN JSON_OBJECT() ELSE state END,
                next_check_at = CASE
                    WHEN last_probe_at IS NULL
                      OR last_probe_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
                    THEN CASE WHEN next_check_at IS NULL OR next_check_at > CURRENT_TIMESTAMP
                         THEN CURRENT_TIMESTAMP ELSE next_check_at END
                    ELSE next_check_at END,
                lease_owner = CASE
                    WHEN last_probe_at IS NULL
                      OR last_probe_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
                    THEN NULL ELSE lease_owner END,
                lease_token = CASE
                    WHEN last_probe_at IS NULL
                      OR last_probe_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
                    THEN NULL ELSE lease_token END,
                lease_until = CASE
                    WHEN last_probe_at IS NULL
                      OR last_probe_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
                    THEN NULL ELSE lease_until END,
                last_probe_at = CURRENT_TIMESTAMP, version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE device_id = #{deviceId}
            """)
    int probeAndRebaselineIfOffline(@Param("deviceId") String deviceId);

    @Select("""
            SELECT m.* FROM ai_device_proactive_monitor m
            INNER JOIN sys_params g
              ON g.param_code = 'proactive.external_monitoring_enabled'
             AND LOWER(TRIM(g.param_value)) = 'true'
            WHERE m.enabled = 1
              AND m.last_probe_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
              AND m.next_check_at <= CURRENT_TIMESTAMP
              AND (m.lease_until IS NULL OR m.lease_until <= CURRENT_TIMESTAMP)
            ORDER BY m.next_check_at, m.device_id, m.monitor_type
            LIMIT #{limit}
            """)
    List<ProactiveMonitorEntity> selectDueCandidates(@Param("limit") int limit);

    @Update("""
            UPDATE ai_device_proactive_monitor m
            INNER JOIN sys_params g
              ON g.param_code = 'proactive.external_monitoring_enabled'
             AND LOWER(TRIM(g.param_value)) = 'true'
            SET m.lease_owner = #{leaseOwner}, m.lease_token = #{leaseToken},
                m.lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 120 SECOND),
                m.version = m.version + 1, m.updated_at = CURRENT_TIMESTAMP
            WHERE m.device_id = #{deviceId} AND m.monitor_type = #{monitorType}
              AND m.enabled = 1
              AND m.last_probe_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
              AND m.next_check_at <= CURRENT_TIMESTAMP
              AND (m.lease_until IS NULL OR m.lease_until <= CURRENT_TIMESTAMP)
            """)
    int claimCas(@Param("deviceId") String deviceId, @Param("monitorType") String monitorType,
            @Param("leaseOwner") String leaseOwner, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE ai_device_proactive_monitor
            SET state = CAST(#{state} AS JSON),
                last_success_at = CASE WHEN #{success} = 1 THEN CURRENT_TIMESTAMP ELSE last_success_at END,
                next_check_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL interval_minutes MINUTE),
                last_error_code = CASE WHEN #{success} = 1 THEN NULL ELSE #{errorCode} END,
                lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE device_id = #{deviceId} AND monitor_type = #{monitorType}
              AND lease_owner = #{leaseOwner} AND lease_token = #{leaseToken}
              AND lease_until > CURRENT_TIMESTAMP
            """)
    int completeCas(@Param("deviceId") String deviceId, @Param("monitorType") String monitorType,
            @Param("leaseOwner") String leaseOwner, @Param("leaseToken") String leaseToken,
            @Param("success") boolean success, @Param("state") String state,
            @Param("errorCode") String errorCode);
}
