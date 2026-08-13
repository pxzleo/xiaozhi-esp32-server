package xiaozhi.modules.device.proactive;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProactiveDeliveryRoutingDao {
    record AccountRow(Long userId, String defaultDeviceIds,
            String locationAuthorityMobileInstanceId, Integer version) {}
    record PlaceRow(Long userId, String placeId, String placeName, String deviceIds) {}
    record DeviceRouteRow(Long userId, String deviceId, String fixedPlaceId) {}
    record LocationRow(Long userId, String mobileInstanceId, String placeId,
            String transition, Date observedAt) {}

    @Select("SELECT user_id,default_device_ids,location_authority_mobile_instance_id,version "
            + "FROM ai_proactive_delivery_route_account WHERE user_id=#{userId} FOR UPDATE")
    AccountRow selectAccountForUpdate(@Param("userId") Long userId);

    @Select("SELECT user_id,default_device_ids,location_authority_mobile_instance_id,version "
            + "FROM ai_proactive_delivery_route_account WHERE user_id=#{userId}")
    AccountRow selectAccount(@Param("userId") Long userId);

    @Select("SELECT user_id,place_id,place_name,device_ids FROM ai_proactive_delivery_route_place "
            + "WHERE user_id=#{userId} ORDER BY place_name,place_id")
    List<PlaceRow> selectPlaces(@Param("userId") Long userId);

    @Select("SELECT user_id,device_id,fixed_place_id FROM ai_proactive_device_route "
            + "WHERE user_id=#{userId}")
    List<DeviceRouteRow> selectDeviceRoutes(@Param("userId") Long userId);

    @Select("SELECT user_id,mobile_instance_id,place_id,transition,observed_at "
            + "FROM ai_proactive_user_location WHERE user_id=#{userId}")
    LocationRow selectLocation(@Param("userId") Long userId);

    @Select("SELECT user_id,mobile_instance_id,place_id,transition,observed_at "
            + "FROM ai_proactive_user_location WHERE user_id=#{userId} "
            + "AND mobile_instance_id=#{mobileInstanceId} AND transition IN ('entered','dwelled') "
            + "AND observed_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 12 HOUR)")
    LocationRow selectFreshLocation(@Param("userId") Long userId,
            @Param("mobileInstanceId") String mobileInstanceId);

    @Select("""
            SELECT jt.device_id
            FROM ai_proactive_user_location l
            INNER JOIN ai_proactive_delivery_route_place p
              ON p.user_id=l.user_id AND p.place_id=l.place_id
            INNER JOIN JSON_TABLE(p.device_ids, '$[*]'
              COLUMNS(device_id VARCHAR(32) PATH '$')) jt
            WHERE l.user_id=#{userId} AND l.mobile_instance_id=#{mobileInstanceId}
              AND l.transition IN ('entered','dwelled') AND l.observed_at>#{freshAfter}
            ORDER BY jt.device_id
            """)
    List<String> selectActivePlaceDeviceIds(@Param("userId") Long userId,
            @Param("mobileInstanceId") String mobileInstanceId,
            @Param("freshAfter") Date freshAfter);

    @Insert("INSERT INTO ai_proactive_delivery_route_account "
            + "(user_id,default_device_ids,location_authority_mobile_instance_id,version,updated_at) "
            + "VALUES (#{userId},#{defaultIds},#{authorityId},1,CURRENT_TIMESTAMP(3))")
    int insertAccount(@Param("userId") Long userId, @Param("defaultIds") String defaultIds,
            @Param("authorityId") String authorityId);

    @Update("UPDATE ai_proactive_delivery_route_account SET default_device_ids=#{defaultIds}, "
            + "location_authority_mobile_instance_id=#{authorityId},version=version+1,"
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=#{userId} AND version=#{version}")
    int updateAccountCas(@Param("userId") Long userId, @Param("defaultIds") String defaultIds,
            @Param("authorityId") String authorityId, @Param("version") int version);

    @Update("UPDATE ai_proactive_delivery_route_account SET "
            + "location_authority_mobile_instance_id=#{authorityId},version=version+1,"
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE user_id=#{userId} AND version=#{version}")
    int updateAuthorityCas(@Param("userId") Long userId, @Param("authorityId") String authorityId,
            @Param("version") int version);

    @Delete("DELETE FROM ai_proactive_delivery_route_place WHERE user_id=#{userId}")
    int deletePlaces(@Param("userId") Long userId);

    @Insert("INSERT INTO ai_proactive_delivery_route_place "
            + "(user_id,place_id,place_name,device_ids,updated_at) VALUES "
            + "(#{row.userId},#{row.placeId},#{row.placeName},#{row.deviceIds},CURRENT_TIMESTAMP(3))")
    int insertPlace(@Param("row") PlaceRow row);

    @Delete("DELETE FROM ai_proactive_device_route WHERE user_id=#{userId}")
    int deleteDeviceRoutes(@Param("userId") Long userId);

    @Insert("INSERT INTO ai_proactive_device_route "
            + "(user_id,device_id,fixed_place_id,updated_at) VALUES "
            + "(#{row.userId},#{row.deviceId},#{row.fixedPlaceId},CURRENT_TIMESTAMP(3))")
    int insertDeviceRoute(@Param("row") DeviceRouteRow row);

    @Insert("""
            INSERT INTO ai_proactive_user_location
              (user_id,mobile_instance_id,place_id,transition,observed_at,updated_at)
            SELECT #{userId},#{mobileInstanceId},#{placeId},#{transition},#{observedAt},CURRENT_TIMESTAMP(3)
            FROM ai_proactive_delivery_route_account a
            WHERE a.user_id=#{userId}
              AND a.location_authority_mobile_instance_id=#{mobileInstanceId}
            ON DUPLICATE KEY UPDATE
              place_id=IF(VALUES(observed_at)>observed_at,VALUES(place_id),place_id),
              transition=IF(VALUES(observed_at)>observed_at,VALUES(transition),transition),
              observed_at=GREATEST(observed_at,VALUES(observed_at)),updated_at=CURRENT_TIMESTAMP(3)
            """)
    int recordAuthoritativeLocation(@Param("userId") Long userId,
            @Param("mobileInstanceId") String mobileInstanceId, @Param("placeId") String placeId,
            @Param("transition") String transition, @Param("observedAt") Date observedAt);

    @Insert("""
            INSERT INTO ai_proactive_delivery_route_place
              (user_id,place_id,place_name,device_ids,updated_at)
            SELECT #{userId},#{placeId},#{placeName},JSON_ARRAY(),CURRENT_TIMESTAMP(3)
            FROM ai_proactive_delivery_route_account a
            WHERE a.user_id=#{userId}
              AND a.location_authority_mobile_instance_id=#{mobileInstanceId}
            ON DUPLICATE KEY UPDATE place_name=VALUES(place_name),updated_at=CURRENT_TIMESTAMP(3)
            """)
    int upsertObservedPlace(@Param("userId") Long userId,
            @Param("mobileInstanceId") String mobileInstanceId,
            @Param("placeId") String placeId, @Param("placeName") String placeName);
}
