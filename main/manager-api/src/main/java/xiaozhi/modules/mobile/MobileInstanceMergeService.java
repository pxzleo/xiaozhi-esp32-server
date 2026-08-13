package xiaozhi.modules.mobile;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MergeRequest;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDao;

@Service
public class MobileInstanceMergeService {
    private static final String DEFAULT_SENSITIVITY = "balanced";
    private static final String DEFAULT_CATEGORIES =
            "security,call,parcel,appointment,message,other";
    private final MobileInstanceDao instanceDao;
    private final ProactiveDeliveryRoutingDao routingDao;

    public MobileInstanceMergeService(MobileInstanceDao instanceDao,
            ProactiveDeliveryRoutingDao routingDao) {
        this.instanceDao = instanceDao;
        this.routingDao = routingDao;
    }

    @Transactional(rollbackFor = Exception.class)
    public void merge(Long userId, MergeRequest request) {
        if (userId == null || new HashSet<>(request.duplicateDeviceIds()).size()
                != request.duplicateDeviceIds().size()
                || request.duplicateDeviceIds().contains(request.canonicalDeviceId())) {
            throw new RenException("手机设备合并参数无效");
        }
        MobileInstanceEntity selectedCanonical = instanceDao.selectByDeviceForUpdate(
                userId, request.canonicalDeviceId());
        if (selectedCanonical == null) throw new RenException("权威手机设备不存在");
        MobileInstanceEntity canonical = instanceDao.selectCanonicalByInstanceForUpdate(
                selectedCanonical.getMobileInstanceId());
        if (canonical == null) throw new RenException("权威手机设备关系无效");
        if (!canonical.getDeviceId().equals(request.canonicalDeviceId())) {
            throw new RenException("权威手机设备必须选择当前显示记录");
        }
        LinkedHashSet<String> sourceCanonicalIds = new LinkedHashSet<>();
        sourceCanonicalIds.add(canonical.getCanonicalInstanceId());
        for (String duplicateDeviceId : request.duplicateDeviceIds()) {
            MobileInstanceEntity duplicate = instanceDao.selectByDeviceForUpdate(userId, duplicateDeviceId);
            if (duplicate == null || !canonical.getAgentId().equals(duplicate.getAgentId())) {
                throw new RenException("待合并手机设备不存在或智能体不一致");
            }
            sourceCanonicalIds.add(duplicate.getCanonicalInstanceId());
        }
        List<MobileInstanceEntity> members = sourceCanonicalIds.stream()
                .flatMap(id -> instanceDao.selectCanonicalGroupForUpdate(userId, id).stream())
                .toList();
        LinkedHashSet<String> stableKeys = members.stream()
                .map(MobileInstanceEntity::getStableDeviceKey)
                .filter(key -> key != null && !key.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (stableKeys.size() > 1) {
            throw new RenException("选中的记录来自不同手机，不能合并");
        }
        inheritAlertSettings(userId, canonical, members);
        String stableKey = stableKeys.stream().findFirst().orElse(null);
        for (String sourceCanonicalId : sourceCanonicalIds) {
            instanceDao.clearStableKeysInGroup(userId, sourceCanonicalId);
        }
        for (String sourceCanonicalId : sourceCanonicalIds) {
            if (!canonical.getCanonicalInstanceId().equals(sourceCanonicalId)) {
                migrateLocationOwnership(userId, sourceCanonicalId,
                        canonical.getCanonicalInstanceId());
                instanceDao.mergeCanonicalGroup(userId, sourceCanonicalId,
                        canonical.getCanonicalInstanceId());
            }
        }
        if (stableKey != null) {
            if (instanceDao.setStableDeviceKey(userId,
                    canonical.getMobileInstanceId(), stableKey) != 1) {
                throw new RenException("手机稳定身份继承失败");
            }
        }
    }

    private void migrateLocationOwnership(Long userId, String sourceId, String targetId) {
        List<ProactiveDeliveryRoutingDao.PlaceCatalogRow> places = routingDao
                .selectPlaceCatalogForUpdate(userId);
        boolean conflict = places.stream().filter(place -> sourceId.equals(place.sourceMobileInstanceId()))
                .anyMatch(source -> places.stream().anyMatch(target -> target.placeId().equals(source.placeId())
                        && targetId.equals(target.sourceMobileInstanceId())));
        if (conflict) throw new RenException("合并手机包含重复地点ID，请先删除其中一侧地点");
        routingDao.migrateAuthorityMobile(userId, sourceId, targetId);
        routingDao.migratePlaceCatalogOwner(userId, sourceId, targetId);
        routingDao.migrateObservedLocationOwner(userId, sourceId, targetId);
    }

    private void inheritAlertSettings(Long userId, MobileInstanceEntity canonical,
            List<MobileInstanceEntity> members) {
        if (!isDefaultSettings(canonical)) return;
        MobileInstanceEntity source = members.stream()
                .filter(member -> !isDefaultSettings(member))
                .max(java.util.Comparator.comparing(this::settingsTimestamp))
                .orElse(null);
        if (source == null) return;
        String sensitivity = org.apache.commons.lang3.StringUtils.defaultIfBlank(
                source.getAlertSensitivity(), DEFAULT_SENSITIVITY);
        String categories = org.apache.commons.lang3.StringUtils.defaultIfBlank(
                source.getAlertCategories(), DEFAULT_CATEGORIES);
        if (instanceDao.updateAlertSettings(userId, canonical.getMobileInstanceId(),
                sensitivity, categories) != 1) {
            throw new RenException("手机提醒设置继承失败");
        }
        canonical.setAlertSensitivity(sensitivity);
        canonical.setAlertCategories(categories);
    }

    private boolean isDefaultSettings(MobileInstanceEntity entity) {
        String sensitivity = org.apache.commons.lang3.StringUtils.defaultIfBlank(
                entity.getAlertSensitivity(), DEFAULT_SENSITIVITY);
        String categories = org.apache.commons.lang3.StringUtils.defaultIfBlank(
                entity.getAlertCategories(), DEFAULT_CATEGORIES);
        return DEFAULT_SENSITIVITY.equals(sensitivity) && DEFAULT_CATEGORIES.equals(categories);
    }

    private long settingsTimestamp(MobileInstanceEntity entity) {
        java.util.Date date = entity.getUpdatedAt() != null
                ? entity.getUpdatedAt() : entity.getLastConnectedAt();
        return date == null ? Long.MIN_VALUE : date.getTime();
    }
}
