export function buildMobileMergeSelection(devices) {
  const selected = (Array.isArray(devices) ? devices : [])
    .filter(device => device && device.selected && device.model === 'android-mobile');
  if (selected.length < 2) return null;
  const canonical = [...selected].sort((left, right) =>
    (right.lastConnectedAtTimestamp || right.rawBindTime || 0) -
    (left.lastConnectedAtTimestamp || left.rawBindTime || 0))[0];
  return {
    canonicalDeviceId: canonical.device_id,
    duplicateDeviceIds: selected
      .filter(device => device.device_id !== canonical.device_id)
      .map(device => device.device_id),
    count: selected.length,
  };
}
