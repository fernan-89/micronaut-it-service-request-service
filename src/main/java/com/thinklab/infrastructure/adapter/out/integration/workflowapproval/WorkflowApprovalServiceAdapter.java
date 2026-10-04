package com.thinklab.infrastructure.adapter.out.integration.workflowapproval;

import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.port.ApprovalServicePort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound Adapter for the workflow-approval Service Domain. Implements the Domain Port, so no Micronaut HTTP client detail leaks into
 * the Application or Domain layers.
 *
 * <p><b>Synchronous integration (ADR-032):</b> {@link #captureDecision} reads the resolved {@code status} back in the same response,
 * which is exactly what makes the synchronous choice worthwhile for this caller. {@code subjectType} is always the literal
 * {@code "ServiceRequest"}, an implementation detail of this adapter.
 */
@Singleton
public class WorkflowApprovalServiceAdapter implements ApprovalServicePort {

    private static final Logger log = LoggerFactory.getLogger(WorkflowApprovalServiceAdapter.class);
    private static final String SUBJECT_TYPE = "ServiceRequest";
    private static final String UNAVAILABLE = "Dependency Failure: Workflow Approval Service is currently unavailable";

    private final WorkflowApprovalApiClient apiClient;

    public WorkflowApprovalServiceAdapter(WorkflowApprovalApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public Mono<UUID> initiateApprovalRequest(UUID organisationId, UUID serviceRequestId, UUID requesterId, UUID policyId, String executor) {
        log.debug("[INTEGRATION] Filing ApprovalRequest on workflow-approval-service for ServiceRequest: {}", serviceRequestId);

        return apiClient.initiate(organisationId.toString(), executor,
                        new InitiateApprovalRequestApiRequest(SUBJECT_TYPE, serviceRequestId, requesterId, policyId))
                .map(ApprovalRequestApiResponse::id)
                .doOnError(error -> log.error("[INTEGRATION FAILURE] Failed to file ApprovalRequest for ServiceRequest: {}", serviceRequestId, error))
                .onErrorMap(error -> new IllegalStateException(UNAVAILABLE, error));
    }

    @Override
    public Mono<ApprovalOutcome> captureDecision(UUID approvalRequestId, UUID approverId, DecisionOutcome outcome, String comment, String executor) {
        log.debug("[INTEGRATION] Forwarding decision [{}] from approver [{}] to workflow-approval-service for ApprovalRequest: {}",
                outcome, approverId, approvalRequestId);

        return apiClient.captureDecision(approvalRequestId, executor, new CaptureDecisionApiRequest(outcome.name(), comment))
                .map(response -> ApprovalOutcome.valueOf(response.status()))
                .doOnError(error -> log.error("[INTEGRATION FAILURE] Failed to capture decision for ApprovalRequest: {}", approvalRequestId, error))
                .onErrorMap(WorkflowApprovalServiceAdapter::relayConflict);
    }

    @Override
    public Mono<Void> cancelApprovalRequest(UUID approvalRequestId, String executor) {
        log.debug("[INTEGRATION] Withdrawing ApprovalRequest {} on workflow-approval-service", approvalRequestId);

        return apiClient.cancel(approvalRequestId, executor)
                .doOnError(error -> log.error("[INTEGRATION FAILURE] Failed to withdraw ApprovalRequest: {}", approvalRequestId, error))
                .onErrorMap(error -> new IllegalStateException(UNAVAILABLE, error));
    }

    /**
     * A 409 from workflow-approval on a decision (an approver who is not eligible, an approval already decided) is a refusal the caller
     * should read, so it is relayed as this service's own 409 with the reason; any other failure stays a dependency failure.
     */
    static Throwable relayConflict(Throwable error) {
        if (error instanceof HttpClientResponseException http && http.getStatus() == HttpStatus.CONFLICT) {
            String reason = http.getResponse().getBody(Map.class).map(body -> body.get("detail")).map(String::valueOf).orElse(http.getMessage());
            return new InvalidServiceRequestStatusException(reason);
        }
        return new IllegalStateException(UNAVAILABLE, error);
    }

    @Serdeable
    @Introspected
    record InitiateApprovalRequestApiRequest(String subjectType, UUID subjectId, UUID requesterId, UUID policyId) {}

    @Serdeable
    @Introspected
    record CaptureDecisionApiRequest(String outcome, String comment) {}

    @Serdeable
    @Introspected
    record ApprovalRequestApiResponse(UUID id, String status) {}
}

/**
 * Declarative Micronaut HTTP Client for the workflow-approval Service Domain. Package-private: an integration detail of the adapter. The
 * 'id' maps to {@code micronaut.http.services.workflow-approval-service} in application.yml.
 */
@Client(id = "workflow-approval-service", path = "/workflow-approval/v1")
interface WorkflowApprovalApiClient {

    @Post("/initiate")
    Mono<WorkflowApprovalServiceAdapter.ApprovalRequestApiResponse> initiate(
            @Header("X-Tenant-Id") String tenantId,
            @Header("X-Executor") String executor,
            @Body WorkflowApprovalServiceAdapter.InitiateApprovalRequestApiRequest request
    );

    @Put("/{id}/decision/capture")
    Mono<WorkflowApprovalServiceAdapter.ApprovalRequestApiResponse> captureDecision(
            @PathVariable UUID id,
            @Header("X-Executor") String executor,
            @Body WorkflowApprovalServiceAdapter.CaptureDecisionApiRequest request
    );

    @Put("/{id}/control/cancel")
    Mono<Void> cancel(@PathVariable UUID id, @Header("X-Executor") String executor);
}
