package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * DTO for making a ServiceRequest (BIAN Behavior Qualifier: {@code initiate}): which catalog item and the answers to its questions.
 * {@code requesterId} is required when staff request on someone's behalf and ignored for a REQUESTER (ADR-031). Answers are stored with
 * the request: no personal data in them.
 */
@Serdeable
public record InitiateServiceRequestRequest(
        @NotNull(message = "Catalog item is required")
        UUID catalogItemId,

        @Nullable
        Map<String, String> answers,

        @Nullable
        UUID requesterId
) {}
