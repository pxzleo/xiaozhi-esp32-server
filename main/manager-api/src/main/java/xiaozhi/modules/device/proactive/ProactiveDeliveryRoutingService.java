package xiaozhi.modules.device.proactive;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.DeviceRouteUpdate;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.DeviceView;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.PlaceUpdate;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.PlaceView;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.RouteUpdate;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.RouteView;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.PlaceDirectoryUpdate;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.PlaceDirectoryItem;
import xiaozhi.modules.device.service.DeviceDisplayName;
import xiaozhi.modules.mobile.MobileInstanceDao;
import xiaozhi.modules.mobile.MobileInstanceEntity;
import xiaozhi.modules.sys.dao.SysUserDao;

@Service
public class ProactiveDeliveryRoutingService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final long LOCATION_MAX_AGE_HOURS = 12;
    private final DeviceDao deviceDao;
    private final ProactiveDeliveryRoutingDao routingDao;
    private final MobileInstanceDao mobileDao;
    private final SysUserDao userDao;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProactiveDeliveryRoutingService(DeviceDao deviceDao,
            ProactiveDeliveryRoutingDao routingDao, MobileInstanceDao mobileDao,
            SysUserDao userDao) {
        this.deviceDao = deviceDao;
        this.routingDao = routingDao;
        this.mobileDao = mobileDao;
        this.userDao = userDao;
    }

    @Transactional
    public List<String> resolveTargetDeviceIds(Long userId) {
        requireUserLock(userId);
        List<DeviceEntity> owned = deviceDao.selectRoutableByUserForUpdate(userId);
        if (owned.isEmpty()) return List.of();
        Set<String> ownedIds = owned.stream().map(DeviceEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        ProactiveDeliveryRoutingDao.AccountRow account = routingDao.selectAccountForUpdate(userId);
        if (account == null) {
            return owned.stream().map(DeviceEntity::getId).sorted().toList();
        }
        if (StringUtils.isNotBlank(account.locationAuthorityMobileInstanceId())) {
            ProactiveDeliveryRoutingDao.LocationRow location = routingDao.selectFreshLocation(
                    userId, account.locationAuthorityMobileInstanceId());
            if (location != null) {
                ProactiveDeliveryRoutingDao.PlaceRow place = routingDao.selectPlaces(userId).stream()
                        .filter(item -> item.placeId().equals(location.placeId())).findFirst().orElse(null);
                if (place != null) return distinctOwned(readIds(place.deviceIds()), ownedIds);
            }
        }
        return distinctOwned(readIds(account.defaultDeviceIds()), ownedIds);
    }

    public void lockUser(Long userId) {
        requireUserLock(userId);
    }

    public RouteView get(Long userId) {
        if (userId == null) throw new RenException("用户未登录");
        List<DeviceEntity> owned = deviceDao.selectRoutableByUser(userId);
        ProactiveDeliveryRoutingDao.AccountRow account = routingDao.selectAccount(userId);
        List<MobileInstanceEntity> mobiles = mobileDao.selectCanonicalByUser(userId);
        Map<String, String> mobileByDevice = new HashMap<>();
        mobiles.forEach(mobile -> mobileByDevice.put(mobile.getDeviceId(), mobile.getMobileInstanceId()));
        Map<String, String> fixedByDevice = new HashMap<>();
        routingDao.selectDeviceRoutes(userId)
                .forEach(route -> fixedByDevice.put(route.deviceId(), route.fixedPlaceId()));
        Set<String> ownedIds = owned.stream().map(DeviceEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> defaults = account == null
                ? owned.stream().map(DeviceEntity::getId).toList()
                : distinctOwned(readIds(account.defaultDeviceIds()), ownedIds);
        List<DeviceView> devices = owned.stream().map(device -> new DeviceView(device.getId(),
                device.getMacAddress(), DeviceDisplayName.resolve(device), mobileByDevice.containsKey(device.getId())
                        ? "mobile" : "speaker", mobileByDevice.get(device.getId()),
                fixedByDevice.get(device.getId()))).toList();
        List<PlaceView> places = routingDao.selectPlaceDirectory(userId).stream()
                .map(place -> new PlaceView(place.placeId(), place.placeName(),
                        distinctOwned(readIds(place.deviceIds()), ownedIds)))
                .toList();
        ProactiveDeliveryRoutingDao.LocationRow location = routingDao.selectLocation(userId);
        boolean fresh = location != null && location.observedAt() != null
                && location.observedAt().after(Date.from(Instant.now()
                        .minus(LOCATION_MAX_AGE_HOURS, ChronoUnit.HOURS)))
                && Set.of("entered", "dwelled").contains(location.transition());
        return new RouteView(defaults,
                account == null ? null : account.locationAuthorityMobileInstanceId(), devices, places,
                fresh ? location.placeId() : null, fresh ? location.observedAt() : null,
                account == null ? 0 : account.version());
    }

    @Transactional
    public RouteView update(Long userId, RouteUpdate request) {
        requireUserLock(userId);
        List<DeviceEntity> owned = deviceDao.selectRoutableByUserForUpdate(userId);
        Set<String> ownedIds = owned.stream().map(DeviceEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        requireDistinctOwned(request.getDefaultDeviceIds(), ownedIds);
        Map<String, ProactiveDeliveryRoutingDao.PlaceCatalogRow> placeCatalog = routingDao
                .selectPlaceCatalogForUpdate(userId).stream().collect(java.util.stream.Collectors.toMap(
                        ProactiveDeliveryRoutingDao.PlaceCatalogRow::placeId, item -> item));
        Set<String> placeIds = new HashSet<>();
        for (PlaceUpdate place : request.getPlaces()) {
            if (!placeIds.add(place.getPlaceId())) throw new RenException("地点ID重复");
            ProactiveDeliveryRoutingDao.PlaceCatalogRow catalog = placeCatalog.get(place.getPlaceId());
            if (catalog == null || !catalog.placeName().equals(place.getPlaceName().trim())) {
                throw new RenException("地点目录已变化，请刷新后重试");
            }
            requireDistinctOwned(place.getDeviceIds(), ownedIds);
        }
        if (!placeIds.equals(placeCatalog.keySet())) throw new RenException("地点目录已变化，请刷新后重试");
        Set<String> routeDevices = new HashSet<>();
        for (DeviceRouteUpdate route : request.getDevices()) {
            if (!ownedIds.contains(route.getDeviceId()) || !routeDevices.add(route.getDeviceId())) {
                throw new RenException("终端地点配置包含无权设备或重复设备");
            }
            if (route.getFixedPlaceId() != null && !placeIds.contains(route.getFixedPlaceId())) {
                throw new RenException("终端固定地点不存在");
            }
        }
        String authority = StringUtils.trimToNull(request.getLocationAuthorityMobileInstanceId());
        if (authority != null) {
            MobileInstanceEntity mobile = mobileDao.selectCanonicalByInstanceForUpdate(authority);
            if (mobile == null || !userId.equals(mobile.getUserId())
                    || !authority.equals(mobile.getMobileInstanceId())) {
                throw new RenException("位置权威手机不存在");
            }
        }
        ProactiveDeliveryRoutingDao.AccountRow current = routingDao.selectAccountForUpdate(userId);
        int changed;
        if (current == null) {
            if (request.getVersion() != 0) throw new RenException("投递路由配置已变化");
            changed = routingDao.insertAccount(userId, writeIds(request.getDefaultDeviceIds()), authority);
        } else {
            changed = routingDao.updateAccountCas(userId, writeIds(request.getDefaultDeviceIds()),
                    authority, request.getVersion());
        }
        if (changed != 1) throw new RenException("投递路由配置已变化");
        routingDao.deleteDeviceRoutes(userId);
        routingDao.deletePlaces(userId);
        for (PlaceUpdate place : request.getPlaces()) {
            if (routingDao.insertPlace(new ProactiveDeliveryRoutingDao.PlaceRow(userId,
                    place.getPlaceId(), placeCatalog.get(place.getPlaceId()).placeName(),
                    writeIds(place.getDeviceIds()))) != 1) throw new RenException("地点路由保存失败");
        }
        for (DeviceRouteUpdate route : request.getDevices()) {
            if (routingDao.insertDeviceRoute(new ProactiveDeliveryRoutingDao.DeviceRouteRow(userId,
                    route.getDeviceId(), route.getFixedPlaceId())) != 1) {
                throw new RenException("终端地点保存失败");
            }
        }
        return get(userId);
    }

    @Transactional
    public RouteView updateLocationAuthority(Long userId, String authority, int version) {
        requireUserLock(userId);
        authority = StringUtils.trimToNull(authority);
        if (authority != null) {
            MobileInstanceEntity mobile = mobileDao.selectCanonicalByInstanceForUpdate(authority);
            if (mobile == null || !userId.equals(mobile.getUserId())
                    || !authority.equals(mobile.getMobileInstanceId())) {
                throw new RenException("位置权威手机不存在");
            }
        }
        ProactiveDeliveryRoutingDao.AccountRow current = routingDao.selectAccountForUpdate(userId);
        int changed;
        if (current == null) {
            if (version != 0) throw new RenException("投递路由配置已变化");
            List<String> all = deviceDao.selectRoutableByUserForUpdate(userId).stream()
                    .map(DeviceEntity::getId).toList();
            changed = routingDao.insertAccount(userId, writeIds(all), authority);
        } else {
            changed = routingDao.updateAuthorityCas(userId, authority, version);
        }
        if (changed != 1) throw new RenException("投递路由配置已变化");
        return get(userId);
    }

    @Transactional
    public void recordLocation(Long userId, String canonicalMobileInstanceId, String placeId,
            String placeName, String transition, Date observedAt) {
        if (userId == null || canonicalMobileInstanceId == null || placeId == null
                || !Set.of("entered", "exited", "dwelled").contains(transition)
                || observedAt == null) return;
        requireUserLock(userId);
        requireCanonicalMobile(userId, canonicalMobileInstanceId);
        requirePlaceOwnership(routingDao.selectPlaceCatalogForUpdate(userId),
                canonicalMobileInstanceId, Set.of(placeId));
        routingDao.upsertObservedPlace(userId, canonicalMobileInstanceId, placeId, placeName);
        routingDao.upsertPlaceCatalog(userId, canonicalMobileInstanceId, placeId, placeName);
        routingDao.recordAuthoritativeLocation(userId, canonicalMobileInstanceId,
                placeId, transition, observedAt);
    }

    @Transactional
    public RouteView syncPlaceDirectory(Long userId, String canonicalMobileInstanceId,
            PlaceDirectoryUpdate request) {
        if (request.getVersion() != 1) throw new RenException("地点目录协议版本无效");
        requireUserLock(userId);
        requireCanonicalMobile(userId, canonicalMobileInstanceId);
        List<ProactiveDeliveryRoutingDao.PlaceCatalogRow> current = routingDao
                .selectPlaceCatalogForUpdate(userId);
        Set<String> placeIds = new HashSet<>();
        requirePlaceOwnership(current, canonicalMobileInstanceId,
                request.getPlaces().stream().map(PlaceDirectoryItem::getPlaceId)
                        .collect(java.util.stream.Collectors.toSet()));
        request.getPlaces().forEach(place -> {
            if (!placeIds.add(place.getPlaceId())) throw new RenException("地点目录包含重复地点");
            routingDao.upsertPlaceCatalog(userId, canonicalMobileInstanceId,
                    place.getPlaceId(), place.getPlaceName().trim());
        });
        List<String> removed = current.stream()
                .filter(item -> canonicalMobileInstanceId.equals(item.sourceMobileInstanceId()))
                .map(ProactiveDeliveryRoutingDao.PlaceCatalogRow::placeId)
                .filter(placeId -> !placeIds.contains(placeId)).toList();
        if (!removed.isEmpty()) {
            routingDao.clearRemovedFixedPlaces(userId, removed);
            routingDao.deleteRemovedPlaceRoutes(userId, removed);
        }
        routingDao.deleteMissingPlaceCatalog(userId, canonicalMobileInstanceId, new ArrayList<>(placeIds));
        return get(userId);
    }

    private void requireCanonicalMobile(Long userId, String mobileInstanceId) {
        MobileInstanceEntity mobile = mobileDao.selectCanonicalByInstanceForUpdate(mobileInstanceId);
        if (mobile == null || !userId.equals(mobile.getUserId())
                || !mobileInstanceId.equals(mobile.getMobileInstanceId())) {
            throw new RenException("地点目录所属手机不存在");
        }
    }

    private void requirePlaceOwnership(List<ProactiveDeliveryRoutingDao.PlaceCatalogRow> current,
            String mobileInstanceId, Set<String> incomingIds) {
        boolean conflict = current.stream().anyMatch(item -> incomingIds.contains(item.placeId())
                && item.sourceMobileInstanceId() != null
                && !mobileInstanceId.equals(item.sourceMobileInstanceId()));
        if (conflict) throw new RenException("地点ID已由另一部手机使用，请重新创建地点");
    }

    private List<String> distinctOwned(List<String> ids, Set<String> ownedIds) {
        return ids.stream().filter(ownedIds::contains).collect(
                java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
    }

    private void requireUserLock(Long userId) {
        if (userId == null || userDao.selectIdForUpdate(userId) == null) {
            throw new RenException("用户不存在");
        }
    }

    private void requireDistinctOwned(List<String> ids, Set<String> ownedIds) {
        if (ids.size() != new HashSet<>(ids).size() || !ownedIds.containsAll(ids)) {
            throw new RenException("接收终端包含无权设备或重复设备");
        }
    }

    private List<String> readIds(String json) {
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException error) {
            throw new RenException("投递路由设备列表损坏", error);
        }
    }

    private String writeIds(List<String> ids) {
        try {
            return mapper.writeValueAsString(ids);
        } catch (JsonProcessingException error) {
            throw new RenException("投递路由设备列表序列化失败", error);
        }
    }
}
