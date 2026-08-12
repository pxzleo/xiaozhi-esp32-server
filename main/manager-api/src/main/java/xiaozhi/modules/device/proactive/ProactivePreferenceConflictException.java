package xiaozhi.modules.device.proactive;

import xiaozhi.common.exception.RenException;

public final class ProactivePreferenceConflictException extends RenException {
    public ProactivePreferenceConflictException() {
        super("主动助理配置已被其他页面修改，请重新读取后再保存");
    }
}
