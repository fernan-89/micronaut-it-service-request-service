package com.thinklab.domain.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for the workflow-approval Service Domain (ADR-032: synchronous HTTP integration, not events). Approval is a shared
 * capability; this service never decides who may approve: the catalog item names a policy there, which may be a chain of stages. This
 * service is the sole client of an {@code ApprovalRequest} filed against one of its requests (subjectType {@code "ServiceRequest"}).
 */
public interface ApprovalServicePort {

    /** Files a new approval request against the policy and returns its sovereign id. */
    Mono<UUID> initiateApprovalRequest(UUID organisationId, UUID serviceRequestId, UUID requesterId, UUID policyId, String executor);

    /** Forwards one approver's decision and returns the approval's post-decision outcome, read in the same cycle. */
    Mono<ApprovalOutcome> captureDecision(UUID approvalRequestId, UUID approverId, DecisionOutcome outcome, String comment, String executor);

    /** Withdraws a pending approval request, so it leaves the approvers' inboxes. */
    Mono<Void> cancelApprovalRequest(UUID approvalRequestId, String executor);

    enum DecisionOutcome { APPROVE, REJECT, RETURN }

    /** Mirrors workflow-approval-service's own status, kept as this service's own copy. */
    enum ApprovalOutcome { PENDING, APPROVED, REJECTED, RETURNED, CANCELLED }
}
