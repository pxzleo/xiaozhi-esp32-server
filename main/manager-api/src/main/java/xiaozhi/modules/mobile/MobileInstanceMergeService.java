package xiaozhi.modules.mobile;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MergeRequest;

@Service
public class MobileInstanceMergeService {
    private final MobileInstanceDao instanceDao;

    public MobileInstanceMergeService(MobileInstanceDao instanceDao) {
        this.instanceDao = instanceDao;
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
        String stableKey = stableKeys.stream().findFirst().orElse(null);
        for (String sourceCanonicalId : sourceCanonicalIds) {
            instanceDao.clearStableKeysInGroup(userId, sourceCanonicalId);
        }
        for (String sourceCanonicalId : sourceCanonicalIds) {
            if (!canonical.getCanonicalInstanceId().equals(sourceCanonicalId)) {
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
}
