package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for ServiceRequest output payload. {@code fulfilment} is the SLA target; it is {@code null} for a request that was cancelled or
 * rejected, which has none to meet. Internal comments are left out for a REQUESTER. {@code approvalRequestId} is present when the catalog
 * item needed approval.
 */
@Serdeable
public record ServiceRequestResponse(
        UUID id,
        UUID organisationId,
        UUID requesterId,
        UUID catalogItemId,
        String catalogItemCode,
        String catalogItemName,
        Map<String, String> answers,
        String status,
        UUID assigneeId,
        UUID approvalRequestId,
        SlaResponse fulfilment,
        Instant startedAt,
        Instant fulfilledAt,
        String fulfilmentNotes,
        List<CommentResponse> comments,
        Instant createdAt,
        Instant updatedAt
) {}
