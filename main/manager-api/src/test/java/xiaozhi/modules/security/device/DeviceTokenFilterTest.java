package xiaozhi.modules.security.device;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class DeviceTokenFilterTest {
    private static final String SECRET = "test-secret";
    private static final String CLIENT = "client-1";
    private static final String DEVICE = "11:22:33:44:55:66";
    private static final long NOW = 2_000_000_000L;

    @Test
    void acceptsMatchingToken() throws Exception {
        assertTrue(DeviceTokenFilter.verify(token(NOW), CLIENT, DEVICE, SECRET, NOW));
    }

    @Test
    void rejectsMissingHeadersWrongSignatureExpiredAndFutureTokens() throws Exception {
        assertFalse(DeviceTokenFilter.verify(null, CLIENT, DEVICE, SECRET, NOW));
        assertFalse(DeviceTokenFilter.verify(token(NOW), "wrong", DEVICE, SECRET, NOW));
        assertFalse(DeviceTokenFilter.verify(token(NOW - DeviceTokenFilter.MAX_AGE_SECONDS - 1),
                CLIENT, DEVICE, SECRET, NOW));
        assertFalse(DeviceTokenFilter.verify(token(NOW + 1), CLIENT, DEVICE, SECRET, NOW));
    }

    private String token(long timestamp) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = hmac.doFinal((CLIENT + "|" + DEVICE + "|" + timestamp)
                .getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature) + "." + timestamp;
    }
}
