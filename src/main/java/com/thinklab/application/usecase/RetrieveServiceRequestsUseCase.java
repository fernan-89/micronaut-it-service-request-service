package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.application.mapper.ServiceRequestMapper;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for the tenant-scoped ServiceRequest collection (BIAN Behavior Qualifier: {@code retrieve}). A REQUESTER is always narrowed to
 * their own requests (a filter in the query, not a post-hoc filter over the tenant) and never sees internal comments.
 */
@Singleton
public class RetrieveServiceRequestsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveServiceRequestsUseCase.class);

    private final ServiceRequestRepository requestRepository;

    public RetrieveServiceRequestsUseCase(ServiceRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    public Flux<ServiceRequestResponse> execute(UUID organisationId, ServiceRequestStatus status, UUID assigneeId, UUID catalogItemId,
                                                boolean openOnly, String executor, String role) {
        log.info("[USE CASE] Retrieving ServiceRequests for organisation: {} status: {} role: {}", organisationId, status, role);

        boolean requester = RequestWorkflow.REQUESTER_ROLE.equals(role);
        UUID requesterFilter = requester ? UUID.fromString(executor) : null;
        Instant now = Instant.now();
        return requestRepository.findAll(organisationId, new ServiceRequestRepository.Filter(status, assigneeId, catalogItemId, requesterFilter, openOnly))
                .map(request -> ServiceRequestMapper.toResponse(request, !requester, now));
    }
}
