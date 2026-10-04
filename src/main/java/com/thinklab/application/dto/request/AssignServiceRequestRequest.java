package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** DTO for {@code assignment/update}: who fulfils the request. */
@Serdeable
public record AssignServiceRequestRequest(
        @NotNull(message = "Assignee is required")
        UUID assigneeId
) {}
