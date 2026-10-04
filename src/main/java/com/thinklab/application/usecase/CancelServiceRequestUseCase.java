package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ServiceRequestAccessDeniedException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for cancelling a ServiceRequest (BIAN Behavior Qualifier: {@code control/cancel}, terminal). Staff only. When the request was
 * still waiting for approval, the approval request is withdrawn on workflow-approval-service afterwards so it leaves the approvers' inboxes;
 * that call is best effort (a failure is logged, never undoes the cancellation: the approval request can be cancelled there).
 */
@Singleton
public class CancelServiceRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelServiceRequestUseCase.class);

    private final ServiceRequestRepository requestRepository;
    private final ApprovalServicePort approvalServicePort;

    public CancelServiceRequestUseCase(ServiceRequestRepository requestRepository, ApprovalServicePort approvalServicePort) {
        this.requestRepository = requestRepository;
        this.approvalServicePort = approvalServicePort;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Cancelling ServiceRequest ID: {}", id);

        if (RequestWorkflow.REQUESTER_ROLE.equals(role)) {
            return Mono.error(new ServiceRequestAccessDeniedException("cancel a request"));
        }
        return requestRepository.findById(id, organisationId)
                .switchIfEmpty(Mono.error(new ServiceRequestNotFoundException(id)))
                .flatMap(request -> {
                    ServiceRequestStatus before = request.getStatus();
                    var entry = request.cancel(executor);
                    return requestRepository.save(request, before, entry)
                            .then(Mono.defer(() -> before == ServiceRequestStatus.PENDING_APPROVAL
                                    ? approvalServicePort.cancelApprovalRequest(request.getApprovalRequestId(), executor)
                                            .onErrorResume(failure -> {
                                                log.warn("[USE CASE] ServiceRequest {} was cancelled but its approval request could not be withdrawn: {}", id, failure.getMessage());
                                                return Mono.empty();
                                            })
                                    : Mono.empty()));
                });
    }
}
