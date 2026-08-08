package xiaozhi.modules.llm.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

class OpenAIStyleLLMServiceAvailabilityTest {
    @Test
    void explicitModelMustBeEnabledLlmWithCompleteConnectionConfig() {
        ModelConfigService configs = mock(ModelConfigService.class);
        OpenAIStyleLLMServiceImpl service = new OpenAIStyleLLMServiceImpl();
        ReflectionTestUtils.setField(service, "modelConfigService", configs);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setModelType("TTS");
        model.setIsEnabled(1);
        model.setConfigJson(new JSONObject()
                .set("base_url", "https://example.test/v1")
                .set("api_key", "secret")
                .set("model_name", "classifier"));
        when(configs.getModelByIdFromCache("model-1")).thenReturn(model);

        assertFalse(service.isAvailable("model-1"));
        model.setModelType("LLM");
        model.setIsEnabled(0);
        assertFalse(service.isAvailable("model-1"));
        model.setIsEnabled(1);
        assertTrue(service.isAvailable("model-1"));
        model.getConfigJson().remove("model_name");
        assertFalse(service.isAvailable("model-1"));
    }
}
