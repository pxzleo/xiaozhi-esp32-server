package xiaozhi.modules.device.proactive;

import java.util.List;
import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ProactiveScheduleActionDao extends BaseMapper<ProactiveScheduleActionEntity> {
    @Select("SELECT * FROM ai_proactive_schedule_action WHERE user_id=#{userId} "
            + "AND revision>#{after} ORDER BY revision LIMIT #{limit}")
    List<ProactiveScheduleActionEntity> selectAfter(@Param("userId") Long userId,
            @Param("after") long after, @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(revision),0) FROM ai_proactive_schedule_action WHERE user_id=#{userId}")
    long highWater(@Param("userId") Long userId);

    @Insert("INSERT INTO ai_proactive_schedule_device_cursor(device_id,acked_action_revision,updated_at) "
            + "VALUES(#{deviceId},#{revision},CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE "
            + "acked_action_revision=GREATEST(acked_action_revision,VALUES(acked_action_revision)),"
            + "updated_at=CURRENT_TIMESTAMP(3)")
    int acknowledge(@Param("deviceId") String deviceId, @Param("revision") long revision);

    @Select("SELECT COALESCE(acked_action_revision,0) FROM ai_proactive_schedule_device_cursor "
            + "WHERE device_id=#{deviceId}")
    Long acknowledged(@Param("deviceId") String deviceId);

    @Select("SELECT * FROM ai_proactive_schedule_action WHERE schedule_id=#{scheduleId} "
            + "ORDER BY revision DESC LIMIT 1")
    ProactiveScheduleActionEntity selectLatestForSchedule(@Param("scheduleId") String scheduleId);
}
