package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;

class AgentMusicPluginValidationTest {

    @Test
    void acceptsOnlyOneMusicPlugin() {
        assertDoesNotThrow(() -> AgentServiceImpl.validateMutuallyExclusiveMusicPlugins(List.of(
                function("SYSTEM_PLUGIN_NETEASE_MUSIC"),
                function("SYSTEM_PLUGIN_WEATHER"))));
    }

    @Test
    void rejectsMultipleMusicPlugins() {
        assertThrows(RenException.class,
                () -> AgentServiceImpl.validateMutuallyExclusiveMusicPlugins(List.of(
                        function("SYSTEM_PLUGIN_MUSIC"),
                        function("SYSTEM_PLUGIN_NETEASE_MUSIC"))));
    }

    @Test
    void duplicatePluginIdsDoNotCreateFalseConflict() {
        assertDoesNotThrow(() -> AgentServiceImpl.validateMutuallyExclusiveMusicPlugins(List.of(
                function("SYSTEM_PLUGIN_NETEASE_MUSIC"),
                function("SYSTEM_PLUGIN_NETEASE_MUSIC"))));
    }

    private AgentUpdateDTO.FunctionInfo function(String pluginId) {
        AgentUpdateDTO.FunctionInfo info = new AgentUpdateDTO.FunctionInfo();
        info.setPluginId(pluginId);
        info.setParamInfo(new HashMap<>());
        return info;
    }
}
