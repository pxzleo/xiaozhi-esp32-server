package xiaozhi.modules.device.netease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class NeteaseProviderClientImplTest {
    @Test
    void qrCheckRequestsCookieAndPreserves803CookieForBindingChain() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        NeteaseProviderClientImpl client = new NeteaseProviderClientImpl(
                restTemplate, new ObjectMapper(), "http://127.0.0.1:3000");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:3000/login/qr/check?timestamp=")))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("key=qr-key"),
                        org.hamcrest.Matchers.containsString("noCookie=true"))))
                .andRespond(withSuccess("{\"code\":803,\"cookie\":\"MUSIC_U=secret\"}",
                        MediaType.APPLICATION_JSON));

        JsonNode payload = client.checkQr("qr-key");

        assertEquals(803, payload.path("code").asInt());
        assertEquals("MUSIC_U=secret", payload.path("cookie").asText());
        server.verify();
    }
}
