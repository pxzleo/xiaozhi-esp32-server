package xiaozhi.modules.device.netease;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;

@Component
public class NeteaseProviderClientImpl implements NeteaseProviderClient {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public NeteaseProviderClientImpl(RestTemplate restTemplate, ObjectMapper objectMapper,
            @Value("${netease-music.api-base-url:http://127.0.0.1:3000}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override public JsonNode createQrKey() { return post("/login/qr/key", new LinkedMultiValueMap<>()); }
    @Override public JsonNode createQr(String key) { return post("/login/qr/create", form("key", key, "qrimg", "true")); }
    @Override public JsonNode checkQr(String key) { return post("/login/qr/check", form("key", key, "noCookie", "true")); }
    @Override public JsonNode account(String cookie) { return post("/user/account", form("cookie", cookie)); }
    @Override public JsonNode logout(String cookie) { return post("/logout", form("cookie", cookie)); }

    private MultiValueMap<String, String> form(String... pairs) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int index = 0; index < pairs.length; index += 2) form.add(pairs[index], pairs[index + 1]);
        return form;
    }

    private JsonNode post(String path, MultiValueMap<String, String> form) {
        long timestamp = System.currentTimeMillis();
        form.add("timestamp", String.valueOf(timestamp));
        URI uri = UriComponentsBuilder.fromUriString(baseUrl).path(path)
                .queryParam("timestamp", timestamp).build(true).toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        try {
            String response = restTemplate.postForObject(uri, new HttpEntity<>(form, headers), String.class);
            if (response == null || response.isBlank()) throw new RenException("网易云服务返回空响应");
            return objectMapper.readTree(response);
        } catch (JsonProcessingException exception) {
            throw new RenException("网易云服务返回无效数据", exception);
        } catch (RestClientException exception) {
            throw new NeteaseProviderUnavailableException("无法连接网易云服务", exception);
        }
    }
}
