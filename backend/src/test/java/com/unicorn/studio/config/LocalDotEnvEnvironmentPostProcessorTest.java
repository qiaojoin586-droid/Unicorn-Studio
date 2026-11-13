package com.unicorn.studio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.service.AliyunRegistrationSmsSender;
import com.unicorn.studio.service.ConsoleRegistrationSmsSender;
import com.unicorn.studio.service.RegistrationSmsSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class LocalDotEnvEnvironmentPostProcessorTest {
    @TempDir Path temporary;

    @Test
    void readsUtf8QuotesCommentsAndExports() throws Exception {
        Path file = temporary.resolve(".env");
        Files.writeString(file, "\uFEFF# 配置\nexport KEY=unit-id\nSECRET=\"unit=a#b\" # 备注\nSIGN='恒创联众'\nEMPTY=\n", StandardCharsets.UTF_8);
        assertThat(LocalDotEnvEnvironmentPostProcessor.read(file)).containsExactlyInAnyOrderEntriesOf(
                Map.of("KEY", "unit-id", "SECRET", "unit=a#b", "SIGN", "恒创联众", "EMPTY", ""));
    }

    @Test
    void bindsExternalFileWithEnvironmentTakingPrecedence() throws Exception {
        Path file = temporary.resolve(".env");
        Files.writeString(file, "ALIBABA_CLOUD_ACCESS_KEY_ID=file-id\nALIBABA_CLOUD_ACCESS_KEY_SECRET=unit-secret\nALIYUN_SMS_SIGN_NAME=恒创联众\n");
        var environment = new MockEnvironment().withProperty("APP_ENV_FILE", file.toString())
                .withProperty("ALIBABA_CLOUD_ACCESS_KEY_ID", "environment-id");
        environment.getPropertySources().addLast(new MapPropertySource("application", Map.of(
                "app.public.registration-sms.provider", "${REGISTRATION_SMS_PROVIDER:aliyun}",
                "app.public.registration-sms.aliyun.access-key-id", "${ALIBABA_CLOUD_ACCESS_KEY_ID:}",
                "app.public.registration-sms.aliyun.access-key-secret", "${ALIBABA_CLOUD_ACCESS_KEY_SECRET:}",
                "app.public.registration-sms.aliyun.sign-name", "${ALIYUN_SMS_SIGN_NAME:}",
                "app.public.registration-sms.aliyun.template-code", "${ALIYUN_SMS_TEMPLATE_CODE:100001}")));
        new LocalDotEnvEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        var properties = Binder.get(environment).bind("app.public.registration-sms", Bindable.of(RegistrationSmsProperties.class)).get();
        assertThat(properties.aliyun().accessKeyId()).isEqualTo("environment-id");
        assertThat(properties.aliyun().accessKeySecret()).isEqualTo("unit-secret");
        assertThat(properties.aliyun().signName()).isEqualTo("恒创联众");
        assertThat(properties.aliyun().templateCode()).isEqualTo("100001");
        assertThat(properties.toString()).doesNotContain("unit-secret", "environment-id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unit-secret-without-equals", "SECRET='unit-secret", "SECRET=\"unit-secret\"invalid"})
    void parseErrorsDoNotIncludeSecrets(String line) throws Exception {
        Path file = temporary.resolve(".env");Files.writeString(file, line);
        assertThatThrownBy(() -> LocalDotEnvEnvironmentPostProcessor.read(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 1 行").hasMessageNotContaining("unit-secret");
    }

    @Test
    void explicitMissingFileFailsClearly() {
        var environment = new MockEnvironment().withProperty("APP_ENV_FILE", temporary.resolve("missing").toString());
        assertThatThrownBy(() -> new LocalDotEnvEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication()))
                .hasMessageContaining("APP_ENV_FILE");
    }

    @Test
    void selectsExactlyOneSender() {
        var runner = new ApplicationContextRunner().withUserConfiguration(RegistrationSmsConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new);
        runner.run(context -> assertThat(context).hasSingleBean(RegistrationSmsSender.class)
                .getBean(RegistrationSmsSender.class).isInstanceOf(AliyunRegistrationSmsSender.class));
        runner.withPropertyValues("app.public.registration-sms.provider=console").run(context -> assertThat(context)
                .hasSingleBean(RegistrationSmsSender.class).getBean(RegistrationSmsSender.class).isInstanceOf(ConsoleRegistrationSmsSender.class));
        runner.withPropertyValues("app.public.registration-sms.provider=invalid").run(context -> assertThat(context).hasFailed());
    }
}
