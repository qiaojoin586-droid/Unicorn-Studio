package com.unicorn.studio.service;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.aliyun.tea.TeaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.config.RegistrationSmsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.io.IOException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AliyunRegistrationSmsSenderTest {
    private final Client client = mock(Client.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegistrationSmsProperties.Aliyun properties = new RegistrationSmsProperties.Aliyun("unit-access-key", "unit-secret", "恒创联众", "100001");
    private final AliyunRegistrationSmsSender sender = new AliyunRegistrationSmsSender(properties, mapper, client);

    @Test
    void sendsSameCodeWithBoundedTimeoutAndNoAutomaticRetry(CapturedOutput output) throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(response("OK", true));
        sender.send("13900000000", "012345");
        var request = ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        var runtime = ArgumentCaptor.forClass(RuntimeOptions.class);
        verify(client, times(1)).sendSmsVerifyCodeWithOptions(request.capture(), runtime.capture());
        assertThat(request.getValue().getPhoneNumber()).isEqualTo("13900000000");
        assertThat(request.getValue().getSignName()).isEqualTo("恒创联众");
        assertThat(request.getValue().getTemplateCode()).isEqualTo("100001");
        assertThat(mapper.readValue(request.getValue().getTemplateParam(), Map.class)).containsExactlyInAnyOrderEntriesOf(Map.of("code", "012345", "min", "5"));
        assertThat(request.getValue().getValidTime()).isEqualTo(300L);
        assertThat(request.getValue().getInterval()).isEqualTo(60L);
        assertThat(request.getValue().getCodeLength()).isEqualTo(6L);
        assertThat(request.getValue().getReturnVerifyCode()).isFalse();
        assertThat(runtime.getValue().getConnectTimeout()).isEqualTo(5000);
        assertThat(runtime.getValue().getReadTimeout()).isEqualTo(10000);
        assertThat(runtime.getValue().getAutoretry()).isFalse();
        assertThat(output.getAll()).doesNotContain("012345", "13900000000", "unit-secret", "unit-access-key");
    }

    @Test
    void rejectionDoesNotLogSensitiveProviderMessage(CapturedOutput output) throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(response("isv.SIGN_NAME_ILLEGAL", false));
        assertThatThrownBy(() -> sender.send("13900000000", "012345")).isInstanceOfSatisfying(SmsDeliveryException.class,
                error -> assertThat(error.isRejected()).isTrue());
        assertThat(output.getAll()).doesNotContain("unit-secret", "012345", "13900000000");
    }

    @Test
    void networkFailureIsUnknownWithoutLeakingSdkException(CapturedOutput output) throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenThrow(new IOException("unit-secret 012345 13900000000"));
        assertThatThrownBy(() -> sender.send("13900000000", "012345")).isInstanceOfSatisfying(SmsDeliveryException.class, error -> {
            assertThat(error.isRejected()).isFalse();assertThat(error.getCause()).isNull();
            assertThat(error.getMessage()).doesNotContain("unit-secret", "012345");
        });
        assertThat(output.getAll()).doesNotContain("unit-secret", "012345", "13900000000");
    }

    @Test
    void incompleteConfigurationDoesNotInvokeSdk() {
        var incomplete = new AliyunRegistrationSmsSender(new RegistrationSmsProperties.Aliyun(null, null, null, null), mapper, client);
        assertThatThrownBy(incomplete::checkAvailable).isInstanceOf(SmsDeliveryException.class);verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Forbidden.NoPermission", "Forbidden.RAM"})
    void permissionFailureIsDefinitiveWithoutLoggingSdkDetails(String providerCode, CapturedOutput output) throws Exception {
        var denied = new TeaException();
        denied.setCode(providerCode);denied.setStatusCode(403);denied.setMessage("unit-secret 012345");
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenThrow(denied);
        assertThatThrownBy(() -> sender.send("13900000000", "012345")).isInstanceOfSatisfying(SmsDeliveryException.class,
                error -> {
                    assertThat(error.isRejected()).isTrue();
                    assertThat(error.getMessage()).isEqualTo("短信服务未获得发送权限，请联系管理员");
                    assertThat(error.getCause()).isNull();
                });
        assertThat(output.getAll()).doesNotContain("unit-secret", "012345", "13900000000");
    }

    @Test
    void permissionFailureInResponseBodyHasSameSafeMessage(CapturedOutput output) throws Exception {
        when(client.sendSmsVerifyCodeWithOptions(any(), any())).thenReturn(response("Forbidden.NoPermission", false));
        assertThatThrownBy(() -> sender.send("13900000000", "012345")).isInstanceOfSatisfying(SmsDeliveryException.class,
                error -> {
                    assertThat(error.isRejected()).isTrue();
                    assertThat(error.getMessage()).isEqualTo("短信服务未获得发送权限，请联系管理员");
                });
        assertThat(output.getAll()).doesNotContain("unit-secret", "012345", "13900000000");
    }

    private SendSmsVerifyCodeResponse response(String code, boolean success) {
        return new SendSmsVerifyCodeResponse().setBody(new SendSmsVerifyCodeResponseBody().setCode(code).setSuccess(success)
                .setRequestId("unit-request-id").setMessage("unit-secret 012345 13900000000"));
    }
}
