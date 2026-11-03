package com.unicorn.studio.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.admin.service.PresentationService;
import com.unicorn.studio.admin.service.SiteSettingsService;
import com.unicorn.studio.common.GlobalExceptionHandler;
import com.unicorn.studio.common.HtmlSanitizer;
import com.unicorn.studio.controller.PresentationController;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=PresentationControllerSecurityTest.Config.class)
@WebAppConfiguration
class PresentationControllerSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity
    static class Config {
        @Bean SiteSettingsService settings() { return mock(SiteSettingsService.class); }
        @Bean LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }
        @Bean PresentationService presentation(SiteSettingsService settings, Validator validator) { return new PresentationService(settings, validator); }
        @Bean PresentationController publicController(PresentationService presentation) { return new PresentationController(presentation); }
        @Bean PresentationAdminController adminController(PresentationService presentation) { return new PresentationAdminController(presentation); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean SecurityFilterChain testChain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/admin/**").authenticated().anyRequest().permitAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> response.setStatus(401)))
                .build();
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired SiteSettingsService settings;
    private MockMvc mvc;
    private Map<String,String> stored;
    @BeforeEach void setup() {
        reset(settings);
        stored = new HashMap<>();
        when(settings.listGroup("content")).thenAnswer(ignored -> new HashMap<>(stored));
        doAnswer(call -> {
            List<SiteSettingsService.KeyValueRequest> values = call.getArgument(0);
            values.forEach(value -> stored.put(value.key(), value.value()));
            return null;
        }).when(settings).updateAll(anyList());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void resetImageOrigins() { HtmlSanitizer.configureAllowedOrigins(null); }

    @ParameterizedTest
    @ValueSource(strings={"work:list","work:create","work:edit","learning:list","learning:create","learning:edit"})
    void richPreviewAllowsEachContentPermissionWithoutWriting(String permission) throws Exception {
        mvc.perform(post("/api/admin/presentation/preview-html")
            .with(user("editor").authorities(new SimpleGrantedAuthority(permission)))
            .contentType(MediaType.APPLICATION_JSON).content("{\"html\":\"<h2>预览标题</h2>\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.html").value("<h2>预览标题</h2>"));
        verifyNoInteractions(settings);
    }
    @Test void richPreviewRequiresAuthenticationAndContentPermission() throws Exception {
        mvc.perform(post("/api/admin/presentation/preview-html").contentType(MediaType.APPLICATION_JSON).content("{\"html\":\"正文\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/presentation/preview-html")
            .with(user("about-only").authorities(new SimpleGrantedAuthority("about:view")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"html\":\"正文\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(settings);
    }
    @Test void richPreviewUsesSaveSanitizerForFormattingAndImages() throws Exception {
        HtmlSanitizer.configureAllowedOrigins("cdn.example.com");
        String html = "<h2 style=\"color:red\" onclick=\"attack()\">标题</h2><script>attack()</script>"
            + "<img src=\"/uploads/a.png\"><img src=\"https://cdn.example.com/a.png\">"
            + "<img src=\"https://tracker.example.com/a.png\"><img src=\"data:image/png;base64,abc\">";
        mvc.perform(post("/api/admin/presentation/preview-html")
            .with(user("editor").authorities(new SimpleGrantedAuthority("work:edit")))
            .contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(Map.of("html", html))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.html").value(HtmlSanitizer.clean(html)))
            .andExpect(jsonPath("$.data.html", containsString("/uploads/a.png")))
            .andExpect(jsonPath("$.data.html", containsString("cdn.example.com")))
            .andExpect(jsonPath("$.data.html", not(containsString("style="))))
            .andExpect(jsonPath("$.data.html", not(containsString("onclick"))))
            .andExpect(jsonPath("$.data.html", not(containsString("<script"))))
            .andExpect(jsonPath("$.data.html", not(containsString("tracker.example.com"))))
            .andExpect(jsonPath("$.data.html", not(containsString("data:image"))));
        verifyNoInteractions(settings);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"html\":null}","{\"html\":\"\"}","{\"html\":\"  \"}"})
    void richPreviewNormalizesEmptyContent(String body) throws Exception {
        mvc.perform(post("/api/admin/presentation/preview-html")
            .with(user("editor").authorities(new SimpleGrantedAuthority("learning:list")))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.html").value(""));
        verifyNoInteractions(settings);
    }
    @Test void richPreviewRejectsOversizedContentBeforeParsing() throws Exception {
        for (String html : List.of("x".repeat(HtmlSanitizer.MAX_CONTENT_LENGTH + 1), " ".repeat(HtmlSanitizer.MAX_CONTENT_LENGTH + 1))) {
            mvc.perform(post("/api/admin/presentation/preview-html")
                .with(user("editor").authorities(new SimpleGrantedAuthority("work:create")))
                .contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(Map.of("html", html))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(settings);
    }
    @Test void publicEndpointReturnsOnlyFourWhitelistedSectionsWithoutAuthentication() throws Exception {
        stored.put("some.private.key", "never export");
        stored.put("content.presentation.home", "{\"adminSecret\":\"never export\"}");
        mvc.perform(get("/api/site/presentation")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data", aMapWithSize(4))).andExpect(jsonPath("$.data.home.title").isString())
            .andExpect(jsonPath("$.data.footer.inheritBrand").value(false)).andExpect(jsonPath("$.data.home.adminSecret").doesNotExist());
    }
    @ParameterizedTest @CsvSource({"home,carousel:list", "about,about:view", "footer,footer:view", "join,join:view"})
    void sectionReadUsesItsExactExistingPermission(String section, String permission) throws Exception {
        mvc.perform(get("/api/admin/presentation/" + section).with(user("reader").authorities(new SimpleGrantedAuthority(permission))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }
    @ParameterizedTest @CsvSource({"home,carousel:edit", "about,about:edit", "footer,footer:edit", "join,join:edit"})
    void sectionWriteUsesItsExactExistingPermission(String section, String permission) throws Exception {
        String form = switch (section) {
            case "home" -> "{\"title\":\"首页\",\"worksTitle\":\"作品\",\"capabilitiesTitle\":\"能力\",\"directionsTitle\":\"方向\",\"invitationTitle\":\"邀请\",\"mediaMode\":\"carousel\",\"featuredLimit\":3}";
            case "about" -> "{\"principlesTitle\":\"理念\",\"invitationTitle\":\"邀请\"}";
            case "join" -> "{\"invitationText\":\"加入我们\"}";
            default -> "{}";
        };
        mvc.perform(put("/api/admin/presentation/" + section).with(user("editor").authorities(new SimpleGrantedAuthority(permission)))
            .contentType(MediaType.APPLICATION_JSON).content(form))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").isMap());
        verify(settings).updateAll(anyList());
    }
    @ParameterizedTest @CsvSource({"home,carousel:list", "about,about:view", "footer,footer:view", "join,join:view"})
    void readPermissionCannotWriteSection(String section, String permission) throws Exception {
        mvc.perform(put("/api/admin/presentation/" + section).with(user("reader").authorities(new SimpleGrantedAuthority(permission)))
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(settings);
    }
    @Test void permissionForOneSectionCannotReadAnother() throws Exception {
        mvc.perform(get("/api/admin/presentation/home").with(user("about-editor").authorities(new SimpleGrantedAuthority("about:view"))))
            .andExpect(status().isForbidden());
        verifyNoInteractions(settings);
    }
    @Test void unsafeJoinSchemeIsRejectedBeforeWriting() throws Exception {
        mvc.perform(put("/api/admin/presentation/join").with(user("editor").authorities(new SimpleGrantedAuthority("join:edit")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"joinUrl\":\"mqq://join/1\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verify(settings, never()).updateAll(anyList());
    }
    @Test void jsonBooleanIsStrictAndRequiredTitleCannotBeCleared() throws Exception {
        mvc.perform(put("/api/admin/presentation/footer").with(user("editor").authorities(new SimpleGrantedAuthority("footer:edit")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"inheritBrand\":\"false\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(put("/api/admin/presentation/home").with(user("editor").authorities(new SimpleGrantedAuthority("carousel:edit")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verify(settings, never()).updateAll(anyList());
    }
    @Test void savedEmptyAndFalseAreImmediatelyVisibleFromPublicEndpoint() throws Exception {
        mvc.perform(put("/api/admin/presentation/footer").with(user("editor").authorities(new SimpleGrantedAuthority("footer:edit")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"inheritBrand\":false,\"noteTitle\":\"\",\"wordmark\":\"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.noteTitle").value(""));
        mvc.perform(get("/api/site/presentation")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.footer.noteTitle").value(""))
            .andExpect(jsonPath("$.data.footer.wordmark").value(""))
            .andExpect(jsonPath("$.data.footer.inheritBrand").value(false));
    }
    @Test void arbitrarySectionRouteIsNotAvailable() throws Exception {
        mvc.perform(get("/api/admin/presentation/notify").with(user("editor").authorities(new SimpleGrantedAuthority("system:settings"))))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        verifyNoInteractions(settings);
    }
}
