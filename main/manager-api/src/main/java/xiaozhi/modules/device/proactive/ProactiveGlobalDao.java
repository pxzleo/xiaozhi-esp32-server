package xiaozhi.modules.device.proactive;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProactiveGlobalDao {
    @Select("""
            SELECT param_value
            FROM sys_params
            WHERE param_code = 'proactive.external_monitoring_enabled'
            FOR UPDATE
            """)
    String selectExternalMonitoringValueForUpdate();
}
