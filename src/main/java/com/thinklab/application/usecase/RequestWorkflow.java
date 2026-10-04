package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ServiceRequestAccessDeniedException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.RequestAuditEntry;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/**
 * The common shape of every staff action on a service request: refuse a REQUESTER (ADR-031), load the request of the tenant (another
 * tenant's is not found), let the aggregate perform the operation and persist what it did, atomically with its audit entry and only if
 * nobody else moved the request in between (a lost race is a 409 the caller can retry).
 */
@Singleton
public class RequestWorkflow {

    static final String REQUESTER_ROLE = "REQUESTER";

    private final ServiceRequestRepository requestRepository;

    public RequestWorkflow(ServiceRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    /** @param operation what the caller tried to do, in words, for the refusal message */
    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<ServiceRequest, RequestAuditEntry> action) {
        if (REQUESTER_ROLE.equals(role)) {
            return Mono.error(new ServiceRequestAccessDeniedException(operation));
        }
        return requestRepository.findById(id, organisationId)
                .switchIfEmpty(Mono.error(new ServiceRequestNotFoundException(id)))
                .flatMap(request -> {
                    var statusBefore = request.getStatus();
                    RequestAuditEntry entry = action.apply(request);
                    return requestRepository.save(request, statusBefore, entry);
                });
    }
}
