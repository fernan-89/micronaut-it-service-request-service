package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateServiceRequestRequest;
import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.application.mapper.ServiceRequestMapper;
import com.thinklab.domain.exception.CatalogItemNotFoundException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CatalogItemRepository;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for making a ServiceRequest (BIAN Behavior Qualifier: {@code initiate}).
 *
 * <p>Self-service scoping (ADR-031): a REQUESTER can only request for themselves (the requester is forced to the token-derived
 * {@code X-Executor}); staff must name the requester. The catalog item is loaded within the tenant (a REQUESTER can only request what is on
 * offer) and the answers are checked against its questions BEFORE anything is created elsewhere.
 *
 * <p>When the item has an approval policy the approval request is filed on workflow-approval-service FIRST (ADR-032): a request that needs
 * approval must never exist without one. If that service is unavailable the request is not made at all. The reverse failure (the request
 * cannot be stored after its approval was filed) leaves an approval request nobody will decide, which is visible and cancellable there.
 */
@Singleton
public class InitiateServiceRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateServiceRequestUseCase.class);

    private final HashServicePort hashServicePort;
    private final CatalogItemRepository catalogRepository;
    private final ServiceRequestRepository requestRepository;
    private final ApprovalServicePort approvalServicePort;

    public InitiateServiceRequestUseCase(HashServicePort hashServicePort, CatalogItemRepository catalogRepository,
                                         ServiceRequestRepository requestRepository, ApprovalServicePort approvalServicePort) {
        this.hashServicePort = hashServicePort;
        this.catalogRepository = catalogRepository;
        this.requestRepository = requestRepository;
        this.approvalServicePort = approvalServicePort;
    }

    public Mono<ServiceRequestResponse> execute(UUID organisationId, InitiateServiceRequestRequest request, String executor, String role) {
        log.info("[USE CASE] Making a ServiceRequest for organisation: {} item: {}", organisationId, request.catalogItemId());

        return Mono.fromCallable(() -> resolveRequesterId(request, executor, role))
                .flatMap(requesterId -> catalogRepository.findById(request.catalogItemId(), organisationId)
                        .switchIfEmpty(Mono.error(new CatalogItemNotFoundException(request.catalogItemId())))
                        .flatMap(item -> {
                            item.requireRequestable();
                            ServiceRequest.checkAnswers(item, request.answers());
                            return hashServicePort.generateSovereignId("service-request-creation")
                                    .flatMap(requestId -> approvalFor(organisationId, requestId, requesterId, item, executor)
                                            .map(approvalId -> ServiceRequest.createNew(requestId, organisationId, requesterId, item, request.answers(), approvalId.orElse(null), executor)));
                        }))
                .flatMap(requestRepository::create)
                .map(created -> ServiceRequestMapper.toResponse(created, true, Instant.now()));
    }

    private Mono<java.util.Optional<UUID>> approvalFor(UUID organisationId, UUID requestId, UUID requesterId, CatalogItem item, String executor) {
        if (item.getApprovalPolicyId() == null) {
            return Mono.just(java.util.Optional.empty());
        }
        return approvalServicePort.initiateApprovalRequest(organisationId, requestId, requesterId, item.getApprovalPolicyId(), executor).map(java.util.Optional::of);
    }

    private static UUID resolveRequesterId(InitiateServiceRequestRequest request, String executor, String role) {
        if (RequestWorkflow.REQUESTER_ROLE.equals(role)) {
            return UUID.fromString(executor);
        }
        if (request.requesterId() == null) {
            throw new IllegalArgumentException("requesterId is required when staff make a request on someone else's behalf.");
        }
        return request.requesterId();
    }
}
