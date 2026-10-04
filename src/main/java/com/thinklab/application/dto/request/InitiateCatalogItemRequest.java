package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * DTO for CatalogItem creation (BIAN Behavior Qualifier: {@code catalog/initiate}). {@code approvalPolicyId} names a policy on
 * workflow-approval-service (which may be a chain); without it a request starts straight in SUBMITTED. The item is created as a DRAFT.
 */
@Serdeable
public record InitiateCatalogItemRequest(
        @NotBlank(message = "Code is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,39}", message = "A code is letters, digits, dashes and underscores, up to 40 characters")
        String code,

        @NotBlank(message = "Name is required")
        @Size(max = 160, message = "Name must not exceed 160 characters")
        String name,

        @Nullable
        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        @Nullable
        @Size(max = 60, message = "Category must not exceed 60 characters")
        String category,

        @NotNull(message = "Fields are required (an empty list is fine)")
        @Size(max = 20, message = "A catalog item can ask at most 20 questions")
        List<@Valid FieldRequest> fields,

        @Min(value = 1, message = "The fulfilment target is at least 1 hour")
        @Max(value = 2160, message = "The fulfilment target is at most 2160 hours (90 days)")
        int fulfilmentTargetHours,

        @Nullable
        UUID approvalPolicyId
) {}
