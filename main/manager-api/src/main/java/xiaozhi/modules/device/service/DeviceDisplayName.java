package xiaozhi.modules.device.service;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.modules.device.entity.DeviceEntity;

public final class DeviceDisplayName {
    private DeviceDisplayName() {}

    public static String resolve(DeviceEntity device) {
        String configured = StringUtils.trimToNull(device.getDisplayName());
        if (configured != null) return configured;
        String alias = StringUtils.trimToNull(device.getAlias());
        String identifier = StringUtils.defaultString(device.getMacAddress());
        if (alias != null && !normalized(alias).equals(normalized(identifier))) return alias;
        boolean mobile = identifier.startsWith("mob_");
        String suffix = identifier.length() <= 4 ? identifier
                : identifier.substring(identifier.length() - 4);
        return (mobile ? "手机" : "小智音箱") + (suffix.isEmpty() ? "" : " · " + suffix);
    }

    private static String normalized(String value) {
        return value.replace(":", "").replace("-", "").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
