package xiaozhi.modules.device.proactive;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface ProactiveScheduleDao extends BaseMapper<ProactiveScheduleEntity> {
    @Select("SELECT * FROM ai_proactive_schedule WHERE source_device_id=#{deviceId} "
            + "AND source_schedule_id=#{sourceId} FOR UPDATE")
    ProactiveScheduleEntity selectSourceForUpdate(@Param("deviceId") String deviceId,
            @Param("sourceId") String sourceId);
    @Select("SELECT * FROM ai_proactive_schedule WHERE id=#{id} FOR UPDATE")
    ProactiveScheduleEntity selectForUpdate(@Param("id") String id);
    @Update("UPDATE ai_proactive_schedule SET state=#{target},snoozed_until=#{snoozedUntil},"
            + "version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} "
            + "AND version=#{version} AND state IN ('scheduled','triggered','snoozed')")
    int actionCas(@Param("id") String id, @Param("version") int version,
            @Param("target") String target, @Param("snoozedUntil") java.util.Date snoozedUntil);
    @Select("SELECT * FROM ai_proactive_schedule WHERE user_id=#{userId} "
            + "AND state IN ('scheduled','triggered','snoozed') "
            + "ORDER BY COALESCE(snoozed_until,next_trigger_at),id LIMIT 100")
    List<ProactiveScheduleEntity> selectActiveForUser(@Param("userId") Long userId);

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    java.util.Date selectDatabaseNow();

    @Select("SELECT * FROM ai_proactive_schedule WHERE deleted_at IS NULL "
            + "AND state IN ('scheduled','snoozed') "
            + "AND COALESCE(snoozed_until,next_trigger_at)<=CURRENT_TIMESTAMP(3) "
            + "ORDER BY COALESCE(snoozed_until,next_trigger_at),id LIMIT 1")
    ProactiveScheduleEntity selectDueCandidate();

    @Select("SELECT * FROM ai_proactive_schedule WHERE id=#{id} AND deleted_at IS NULL "
            + "AND state IN ('scheduled','snoozed') "
            + "AND COALESCE(snoozed_until,next_trigger_at)<=CURRENT_TIMESTAMP(3) "
            + "FOR UPDATE SKIP LOCKED")
    ProactiveScheduleEntity selectDueForUpdate(@Param("id") String id);

    @Select("SELECT * FROM ai_proactive_schedule WHERE user_id=#{userId} "
            + "AND sync_revision>#{since} ORDER BY sync_revision LIMIT #{limit}")
    List<ProactiveScheduleEntity> selectChanges(@Param("userId") Long userId,
            @Param("since") long since, @Param("limit") int limit);

    @Select("SELECT * FROM ai_device_proactive_event WHERE device_id=#{deviceId} "
            + "AND event_id LIKE CONCAT('schedule-',#{scheduleId},'-%') "
            + "ORDER BY created_at DESC,id DESC LIMIT 1")
    ProactiveEventEntity selectLatestEvent(@Param("deviceId") String deviceId,
            @Param("scheduleId") String scheduleId);
}
