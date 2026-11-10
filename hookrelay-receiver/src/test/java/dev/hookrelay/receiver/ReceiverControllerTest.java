package dev.hookrelay.receiver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReceiverControllerTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChaosSettings chaos = new ChaosSettings(SECRET);
        mvc = MockMvcBuilders.standaloneSetup(new ReceiverController(chaos, Clock.fixed(NOW, ZoneOffset.UTC)))
                .build();
    }

    private static String sign(String id, long ts, String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring(6)), "HmacSHA256"));
        byte[] sig = mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(sig);
    }

    private MockHttpServletRequestBuilder webhook(String id, String body, String signature) {
        return post("/webhooks").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("webhook-id", id)
                .header("webhook-timestamp", Long.toString(NOW.getEpochSecond()))
                .header("webhook-signature", signature)
                .header("hookrelay-event-type", "order.created");
    }

    private MockHttpServletRequestBuilder signed(String id, String body) throws Exception {
        return webhook(id, body, sign(id, NOW.getEpochSecond(), body, SECRET));
    }

    @Test
    void validSignatureIsAcceptedAndRecorded() throws Exception {
        mvc.perform(signed("msg_1", "{\"a\":1}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(get("/received")).andExpect(jsonPath("$[0].webhookId").value("msg_1"))
                .andExpect(jsonPath("$[0].eventType").value("order.created"))
                .andExpect(jsonPath("$[0].verified").value(true))
                .andExpect(jsonPath("$[0].duplicate").value(false));
    }

    @Test
    void badSignatureIsRejected() throws Exception {
        mvc.perform(webhook("msg_2", "{}", "v1,AAAA")).andExpect(status().isUnauthorized());
        mvc.perform(get("/received")).andExpect(jsonPath("$[0].verified").value(false));
    }

    @Test
    void redeliveryOfTheSameIdIsMarkedDuplicate() throws Exception {
        mvc.perform(signed("msg_3", "{}")).andExpect(status().isOk());
        mvc.perform(signed("msg_3", "{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(get("/received")).andExpect(jsonPath("$[0].duplicate").value(true))
                .andExpect(jsonPath("$[1].duplicate").value(false));
    }

    @Test
    void chaosCanForceAStatus() throws Exception {
        mvc.perform(put("/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"forceStatus\":503}"))
                .andExpect(status().isOk());
        mvc.perform(signed("msg_4", "{}")).andExpect(status().isServiceUnavailable());

        mvc.perform(put("/chaos").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(signed("msg_4", "{}")).andExpect(status().isOk());
    }

    @Test
    void theSecretCanBeSetAtRuntime() throws Exception {
        String other = "whsec_" + Base64.getEncoder().encodeToString(new byte[24]);
        mvc.perform(put("/chaos/secret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"" + other + "\"}")).andExpect(status().isOk());
        mvc.perform(webhook("msg_5", "{}", sign("msg_5", NOW.getEpochSecond(), "{}", other)))
                .andExpect(status().isOk());
    }
}
