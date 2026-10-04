package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTO for {@code control/fulfil}: what was delivered. */
@Serdeable
public record FulfilServiceRequestRequest(
        @NotBlank(message = "Notes on what was delivered are required")
        @Size(max = 4000, message = "Notes must not exceed 4000 characters")
        String notes
) {}
