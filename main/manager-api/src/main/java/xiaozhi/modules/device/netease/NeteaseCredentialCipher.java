package xiaozhi.modules.device.netease;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.utils.AESUtils;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
public class NeteaseCredentialCipher {
    private final SysParamsService sysParamsService;

    public NeteaseCredentialCipher(SysParamsService sysParamsService) {
        this.sysParamsService = sysParamsService;
    }

    public String encrypt(String plaintext) {
        return AESUtils.encrypt(requireKey(), plaintext);
    }

    public String decrypt(String ciphertext) {
        return AESUtils.decrypt(requireKey(), ciphertext);
    }

    private String requireKey() {
        String key = sysParamsService.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isBlank(key) || "null".equalsIgnoreCase(key)) {
            throw new IllegalStateException("网易云凭证加密密钥未配置(server.secret)");
        }
        return key;
    }
}
