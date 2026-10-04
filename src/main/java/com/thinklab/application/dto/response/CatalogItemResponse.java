package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Serdeable
public record CatalogItemResponse(
        UUID id,
        UUID organisationId,
        String code,
        String name,
        String description,
        String category,
        List<FieldResponse> fields,
        int fulfilmentTargetHours,
        UUID approvalPolicyId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
