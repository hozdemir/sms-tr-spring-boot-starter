package io.github.hozdemir.smstr.samples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import io.github.hozdemir.smstr.MessageType;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsMessage;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SampleApplicationTest {

    private static final Pattern CODE = Pattern.compile("Your verification code: (\\d{6})");

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxSmsProvider outbox;

    @Autowired
    OrderNotifications notifications;

    @Test
    void otpLoginFlowOverHttp() throws Exception {
        mvc.perform(post("/otp/send").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"0532 123 45 67\"}"))
                .andExpect(status().isAccepted());
        String code = lastCode();

        mvc.perform(post("/otp/send").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"05321234567\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(verify("000000")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("INVALID_CODE"));
        mvc.perform(verify(code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("VERIFIED"));
        mvc.perform(verify(code)).andExpect(jsonPath("$.result").value("NOT_FOUND"));
    }

    @Test
    void rejectsNonMobileNumbers() throws Exception {
        mvc.perform(post("/otp/send").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"0212 123 45 67\"}"))
                .andExpect(status().isBadRequest());
        assertThat(outbox.messages()).isEmpty();
    }

    @Test
    void orderNotificationsSendAndCheckDelivery() {
        SendResult shipped = notifications.orderShipped("+90 532 123 45 67", "TR123");
        SendResult campaign = notifications.weekendCampaign(
                List.of(PhoneNumber.of("5321234567"), PhoneNumber.of("5551112233")), Instant.parse("2026-11-01T07:00:00Z"));

        assertThat(notifications.delivered(shipped)).isTrue();
        SmsMessage sent = outbox.messages().get(1);
        assertThat(sent.type()).isEqualTo(MessageType.COMMERCIAL_INDIVIDUAL);
        assertThat(campaign.recipients()).hasSize(2);
    }

    private org.springframework.test.web.servlet.RequestBuilder verify(String code) {
        return post("/otp/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"05321234567\",\"code\":\"" + code + "\"}");
    }

    private String lastCode() {
        List<SmsMessage> messages = outbox.messages();
        Matcher matcher = CODE.matcher(messages.get(messages.size() - 1).text());
        assertThat(matcher.matches()).isTrue();
        return matcher.group(1);
    }
}
