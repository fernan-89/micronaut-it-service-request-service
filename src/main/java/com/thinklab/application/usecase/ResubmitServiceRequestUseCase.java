package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ResubmitServiceRequestRequest;
import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.application.mapper.ServiceRequestMapper;
import com.thinklab.domain.exception.CatalogItemNotFoundException;
import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.repository.CatalogItemRepository;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Use Case for editing and resubmitting a request an approver returned (BIAN Behavior Qualifier: {@code control/resubmit}, ADR-035). The
 * requester can do it on their own request (anyone else's is a 404), and staff on someone's behalf. The new answers are checked against the
 * item again, a NEW approval request is filed first when the item has a policy (a fresh chain from stage one), and the request is saved
 * with a guard on RETURNED, so a cancel racing the resubmit cannot be overwritten. The fulfilment due date does not move.
 */
@Singleton
public class ResubmitServiceRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResubmitServiceRequestUseCase.class);

    private final ServiceRequestRepository requestRepository;
    private final CatalogItemRepository catalogRepository;
    private final ApprovalServicePort approvalServicePort;

    public ResubmitServiceRequestUseCase(ServiceRequestRepository requestRepository, CatalogItemRepository catalogRepository,
                                         ApprovalServicePort approvalServicePort) {
        this.requestRepository = requestRepository;
        this.catalogRepository = catalogRepository;
        this.approvalServicePort = approvalServicePort;
    }

    public Mono<ServiceRequestResponse> execute(UUID id, UUID organisationId, ResubmitServiceRequestRequest body, String executor, String role) {
        log.info("[USE CASE] Resubmitting ServiceRequest ID: {}", id);

        boolean requester = RequestWorkflow.REQUESTER_ROLE.equals(role);
        return requestRepository.findById(id, organisationId)
                .filter(request -> !requester || request.getRequesterId().equals(UUID.fromString(executor)))
                .switchIfEmpty(Mono.error(new ServiceRequestNotFoundException(id)))
                .flatMap(request -> catalogRepository.findById(request.getCatalogItemId(), organisationId)
                        .switchIfEmpty(Mono.error(new CatalogItemNotFoundException(request.getCatalogItemId())))
                        .flatMap(item -> resubmit(request, item, organisationId, body, executor)))
                .map(request -> ServiceRequestMapper.toResponse(request, !requester, Instant.now()));
    }

    private Mono<ServiceRequest> resubmit(ServiceRequest request, CatalogItem item, UUID organisationId, ResubmitServiceRequestRequest body, String executor) {
        // Refused before any approval is filed: not RETURNED, an unpublished item, or answers the item does not accept.
        if (request.getStatus() != ServiceRequestStatus.RETURNED) {
            return Mono.error(new InvalidServiceRequestStatusException(
                    "Illegal transition: only a RETURNED ServiceRequest can be resubmitted (it is " + request.getStatus() + ")."));
        }
        item.requireRequestable();
        ServiceRequest.checkAnswers(item, body.answers());
        Mono<Optional<UUID>> approval = item.getApprovalPolicyId() == null
                ? Mono.just(Optional.<UUID>empty())
                : approvalServicePort.initiateApprovalRequest(organisationId, request.getId(), request.getRequesterId(), item.getApprovalPolicyId(), executor).map(Optional::of);
        return approval.flatMap(approvalId -> {
            var entry = request.resubmit(item, body.answers(), approvalId.orElse(null), executor);
            return requestRepository.save(request, ServiceRequestStatus.RETURNED, entry).thenReturn(request);
        });
    }
}
