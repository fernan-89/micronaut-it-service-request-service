package com.thinklab.domain.repository;

import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.RequestAuditEntry;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for ServiceRequest persistence (IT Service Request Service Domain).
 *
 * <p>ARCHITECTURAL RULE: Partial State Mutations (ADR-002). {@link #create} is the only whole-document write; every change is one atomic
 * update that also appends its forensic audit entry, and {@link #save} only applies while the request is still in the status it had when
 * it was loaded, so two people working the same request cannot silently overwrite each other (ADR-033). There is no {@code deleteById}.
 * Every lookup is tenant-scoped.
 */
public interface ServiceRequestRepository {

    Mono<ServiceRequest> create(ServiceRequest request);

    Mono<ServiceRequest> findById(UUID id, UUID organisationId);

    Flux<ServiceRequest> findAll(UUID organisationId, Filter filter);

    /** Persists the state the request reached with its audit entry; a lost race is {@link com.thinklab.domain.exception.InvalidServiceRequestStatusException} (409, retry). */
    Mono<Void> save(ServiceRequest request, ServiceRequestStatus expectedStatus, RequestAuditEntry auditEntry);

    Mono<Void> addComment(UUID id, UUID organisationId, Comment comment, RequestAuditEntry auditEntry);

    /**
     * Optional filters of the collection. {@code requesterId} is how the application layer narrows a REQUESTER to their own requests;
     * {@code openOnly} leaves out FULFILLED, CLOSED, CANCELLED and REJECTED.
     */
    record Filter(ServiceRequestStatus status, UUID assigneeId, UUID catalogItemId, UUID requesterId, boolean openOnly) {
    }
}
