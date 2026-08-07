package xiaozhi.modules.model.controller;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class NeteaseMusicLoginControllerTest {

    @Test
    void proxiesQrCreationToTheServerLocalApiWithoutExposingItToTheBrowser() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        NeteaseMusicLoginController controller = new NeteaseMusicLoginController(
                restTemplate,
                new ObjectMapper(),
                "http://127.0.0.1:3000");
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("key", "qr-key");
        params.add("qrimg", "true");
        params.add("timestamp", "123456789");

        server.expect(once(), requestTo("http://127.0.0.1:3000/login/qr/create?timestamp=123456789"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("key=qr-key"),
                        org.hamcrest.Matchers.containsString("qrimg=true"))))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"qrimg\":\"data:image/png;base64,abc\"}}",
                        MediaType.APPLICATION_JSON));

        JsonNode response = controller.createQr(params);

        org.junit.jupiter.api.Assertions.assertEquals(200, response.path("code").asInt());
        server.verify();
    }
}
