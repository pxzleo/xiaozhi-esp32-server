package xiaozhi.modules.config.init;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.device.service.DeviceAddressBookService;
import xiaozhi.modules.sys.service.SysParamsService;

class SystemInitConfigTest {

    @Test
    void fireRedModelCacheIsInvalidatedBeforeServerConfigRebuild() {
        RedisUtils redisUtils = mock(RedisUtils.class);
        ConfigService configService = mock(ConfigService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        DeviceAddressBookService addressBookService = mock(DeviceAddressBookService.class);
        SystemInitConfig systemInitConfig = new SystemInitConfig();
        ReflectionTestUtils.setField(systemInitConfig, "redisUtils", redisUtils);
        ReflectionTestUtils.setField(systemInitConfig, "configService", configService);
        ReflectionTestUtils.setField(systemInitConfig, "sysParamsService", sysParamsService);
        ReflectionTestUtils.setField(
                systemInitConfig, "deviceAddressBookService", addressBookService);
        when(redisUtils.get(RedisKeys.getVersionKey())).thenReturn(Constant.VERSION);

        systemInitConfig.init();

        InOrder order = inOrder(redisUtils, configService);
        order.verify(redisUtils)
                .delete(RedisKeys.getModelConfigById("VAD_FireRedVAD"));
        order.verify(configService).getConfig(false);
    }
}
