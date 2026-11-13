package com.unicorn.studio.vo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;

/** Whitelisted public presentation contract, shared by public reads and section editors. */
public record PresentationVO(Home home, About about, Footer footer, Join join) {
    public record Home(
        @Size(max=120) String eyebrow,
        @NotBlank @Size(max=160) String title,
        @Size(max=2000) String description,
        @Size(max=80) String primaryLabel, @Size(max=2048) String primaryUrl,
        @Size(max=80) String secondaryLabel, @Size(max=2048) String secondaryUrl,
        @Pattern(regexp="|single|carousel") String mediaMode,
        @NotNull @Min(1) @Max(6) Integer featuredLimit,
        @Size(max=120) String worksEyebrow, @NotBlank @Size(max=160) String worksTitle,
        @Size(max=2000) String worksDescription,
        @Size(max=120) String capabilitiesEyebrow, @NotBlank @Size(max=160) String capabilitiesTitle,
        @Size(max=2000) String capabilitiesDescription,
        @Size(max=120) String directionsEyebrow, @NotBlank @Size(max=160) String directionsTitle,
        @Size(max=2000) String directionsDescription,
        @Size(max=120) String invitationEyebrow, @NotBlank @Size(max=160) String invitationTitle,
        @Size(max=2000) String invitationDescription,
        @Size(max=80) String invitationLabel, @Size(max=2048) String invitationUrl,
        @Pattern(regexp="|work|learning|join") String recommendationType,
        @Pattern(regexp="|[1-9][0-9]{0,18}") String recommendationId,
        @Pattern(regexp="|image|video") String backgroundMode,
        @Size(max=2048) String backgroundImageUrl,
        @Size(max=2048) String backgroundVideoUrl) { }
    public record About(
        @Size(max=120) String principlesEyebrow, @NotBlank @Size(max=160) String principlesTitle,
        @Valid @Size(max=8) List<@NotNull @Valid Principle> principles,
        @NotBlank @Size(max=160) String invitationTitle,
        @Size(max=2000) String invitationDescription,
        @Size(max=80) String invitationLabel, @Size(max=2048) String invitationUrl,
        @Pattern(regexp="|image|video") String backgroundMode,
        @Size(max=2048) String backgroundImageUrl,
        @Size(max=2048) String backgroundVideoUrl) { }
    public record Principle(@NotBlank @Size(max=160) String title, @Size(max=1200) String description) { }
    /** Empty footer additions hide optional blocks; false must survive default merging. */
    public record Footer(
        @JsonDeserialize(using=StrictBooleanDeserializer.class) Boolean inheritBrand,
        @JsonDeserialize(using=StrictBooleanDeserializer.class) Boolean inheritContact,
        @Size(max=160) String brandTagline, @Size(max=120) String noteEyebrow,
        @Size(max=160) String noteTitle, @Size(max=80) String noteLinkLabel,
        @Size(max=2048) String noteLinkUrl, @Size(max=120) String wordmark) { }
    public record Join(
        @Size(max=40) String platform, @Size(max=40) String groupNumber,
        @Size(max=2048) String joinUrl, @Size(max=1000) String phoneHelp,
        @NotBlank @Size(max=500) String invitationText) { }

    public static class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_TRUE) return true;
            if (parser.currentToken() == JsonToken.VALUE_FALSE) return false;
            throw JsonMappingException.from(parser, "Expected a JSON boolean");
        }
    }
}
