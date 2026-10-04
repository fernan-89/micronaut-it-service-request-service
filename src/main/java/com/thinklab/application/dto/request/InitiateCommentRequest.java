package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTO for {@code comment/initiate}. {@code internal} notes are never shown to a REQUESTER, and a REQUESTER cannot write one. */
@Serdeable
public record InitiateCommentRequest(
        @NotBlank(message = "Text is required")
        @Size(max = 4000, message = "Text must not exceed 4000 characters")
        String text,

        boolean internal
) {}
