package xiaozhi.modules.mobile;

import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import xiaozhi.modules.mobile.MobileAssistantDTOs.AuthorizeRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.BindRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MessageActionRequest;
import xiaozhi.modules.mobile.MobileEventDTOs.BatchRequest;

public class MobileJsonHttpMessageConverter extends MappingJackson2HttpMessageConverter {
    public MobileJsonHttpMessageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        mapper.registerModule(new JavaTimeModule());
        setObjectMapper(mapper);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return BindRequest.class.equals(clazz) || AuthorizeRequest.class.equals(clazz)
                || MessageActionRequest.class.equals(clazz) || BatchRequest.class.equals(clazz);
    }
}
