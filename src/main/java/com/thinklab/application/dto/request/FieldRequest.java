package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One question of a catalog item: a short key, a label for the person, and whether an answer is mandatory. */
@Serdeable
public record FieldRequest(
        @NotBlank(message = "Question key is required")
        @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9_]{0,39}", message = "A question key is a letter followed by up to 39 letters, digits or underscores")
        String key,

        @NotBlank(message = "Question label is required")
        @Size(max = 200, message = "Question label must not exceed 200 characters")
        String label,

        boolean required
) {}
