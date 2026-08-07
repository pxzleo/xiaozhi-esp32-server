package xiaozhi.modules.model.controller;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import xiaozhi.common.exception.RenException;

@RestController
@RequestMapping("/models/provider/plugin/netease-login")
@Tag(name = "网易云音乐登录")
public class NeteaseMusicLoginController {

    private static final Set<String> KEY_PARAMS = Set.of("timestamp");
    private static final Set<String> CREATE_PARAMS = Set.of("key", "qrimg", "timestamp");
    private static final Set<String> CHECK_PARAMS = Set.of("key", "noCookie", "timestamp");
    private static final Set<String> ACCOUNT_PARAMS = Set.of("cookie", "timestamp");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    public NeteaseMusicLoginController(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${netease-music.api-base-url:http://127.0.0.1:3000}") String apiBaseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
    }

    @PostMapping("/login/qr/key")
    @Operation(summary = "生成网易云音乐登录二维码密钥")
    public JsonNode createQrKey(@RequestParam MultiValueMap<String, String> params) {
        return proxy("/login/qr/key", params, KEY_PARAMS);
    }

    @PostMapping("/login/qr/create")
    @Operation(summary = "生成网易云音乐登录二维码")
    public JsonNode createQr(@RequestParam MultiValueMap<String, String> params) {
        return proxy("/login/qr/create", params, CREATE_PARAMS);
    }

    @PostMapping("/login/qr/check")
    @Operation(summary = "查询网易云音乐扫码状态")
    public JsonNode checkQr(@RequestParam MultiValueMap<String, String> params) {
        return proxy("/login/qr/check", params, CHECK_PARAMS);
    }

    @PostMapping("/user/account")
    @Operation(summary = "读取网易云音乐登录账号")
    public JsonNode getAccount(@RequestParam MultiValueMap<String, String> params) {
        return proxy("/user/account", params, ACCOUNT_PARAMS);
    }

    private JsonNode proxy(String path, MultiValueMap<String, String> params, Set<String> allowedParams) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        allowedParams.forEach(key -> {
            String value = params.getFirst(key);
            if (value != null) {
                form.add(key, value);
            }
        });

        URI target = UriComponentsBuilder.fromUriString(apiBaseUrl)
                .path(path)
                .queryParam("timestamp", form.getFirst("timestamp"))
                .build(true)
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            String response = restTemplate.postForObject(target, new HttpEntity<>(form, headers), String.class);
            if (response == null || response.isBlank()) {
                throw new RenException("网易云音乐登录服务返回空响应");
            }
            return objectMapper.readTree(response);
        } catch (JsonProcessingException exception) {
            throw new RenException("网易云音乐登录服务返回了无效数据", exception);
        } catch (RestClientException exception) {
            throw new RenException("无法连接网易云音乐登录服务", exception);
        }
    }
}
