package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.CaptureApprovalDecisionRequest;
import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.application.mapper.ServiceRequestMapper;
import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.exception.ServiceRequestAccessDeniedException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.port.ApprovalServicePort.ApprovalOutcome;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for forwarding one approver's decision to workflow-approval-service (BIAN Behavior Qualifier: {@code approval/capture}).
 * Staff only. The approver is the {@code X-Executor} (a user id), as that service requires. The outcome is read back in the same cycle: an
 * APPROVED approval (every stage of a chain, if there is one) moves the request to APPROVED, a REJECTED one to REJECTED, and a still
 * PENDING one (a chain with stages left, a quorum not reached) leaves it waiting - this service holds no approval logic of its own.
 */
@Singleton
public class CaptureApprovalDecisionUseCase {

    private static final Logger log = LoggerFactory.getLogger(CaptureApprovalDecisionUseCase.class);

    private final ServiceRequestRepository requestRepository;
    private final ApprovalServicePort approvalServicePort;

    public CaptureApprovalDecisionUseCase(ServiceRequestRepository requestRepository, ApprovalServicePort approvalServicePort) {
        this.requestRepository = requestRepository;
        this.approvalServicePort = approvalServicePort;
    }

    public Mono<ServiceRequestResponse> execute(UUID id, UUID organisationId, CaptureApprovalDecisionRequest request, String executor, String role) {
        log.info("[USE CASE] Capturing approval decision [{}] for ServiceRequest ID: {}", request.outcome(), id);

        if (RequestWorkflow.REQUESTER_ROLE.equals(role)) {
            return Mono.error(new ServiceRequestAccessDeniedException("decide an approval"));
        }
        return requestRepository.findById(id, organisationId)
                .switchIfEmpty(Mono.error(new ServiceRequestNotFoundException(id)))
                .flatMap(serviceRequest -> {
                    if (serviceRequest.getStatus() != ServiceRequestStatus.PENDING_APPROVAL) {
                        return Mono.error(new InvalidServiceRequestStatusException(
                                "Illegal transition: no approval is waiting for a decision on this ServiceRequest (it is " + serviceRequest.getStatus() + ")."));
                    }
                    return approvalServicePort.captureDecision(serviceRequest.getApprovalRequestId(), UUID.fromString(executor), request.outcome(), request.comment(), executor)
                            .flatMap(outcome -> applyOutcome(serviceRequest, outcome, executor));
                });
    }

    private Mono<ServiceRequestResponse> applyOutcome(ServiceRequest serviceRequest, ApprovalOutcome outcome, String executor) {
        if (outcome == ApprovalOutcome.APPROVED) {
            return save(serviceRequest, serviceRequest.approve(executor));
        }
        if (outcome == ApprovalOutcome.REJECTED) {
            return save(serviceRequest, serviceRequest.reject(executor));
        }
        return Mono.just(ServiceRequestMapper.toResponse(serviceRequest, true, Instant.now()));
    }

    private Mono<ServiceRequestResponse> save(ServiceRequest serviceRequest, ServiceRequest.RequestAuditEntry entry) {
        return requestRepository.save(serviceRequest, ServiceRequestStatus.PENDING_APPROVAL, entry)
                .thenReturn(ServiceRequestMapper.toResponse(serviceRequest, true, Instant.now()));
    }
}
