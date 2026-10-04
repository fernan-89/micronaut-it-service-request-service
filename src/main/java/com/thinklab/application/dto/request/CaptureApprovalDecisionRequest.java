package com.thinklab.application.dto.request;

import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for {@code approval/capture}: one approver's vote, forwarded to workflow-approval-service. The approver is the {@code X-Executor}
 * (a user id), exactly as that service expects.
 */
@Serdeable
public record CaptureApprovalDecisionRequest(
        @NotNull(message = "Outcome is required")
        DecisionOutcome outcome,

        @Nullable
        @Size(max = 500, message = "Comment must not exceed 500 characters")
        String comment
) {}
