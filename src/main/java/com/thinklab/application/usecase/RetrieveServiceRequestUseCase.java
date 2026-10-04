package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.application.mapper.ServiceRequestMapper;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for retrieving one ServiceRequest (BIAN Behavior Qualifier: {@code retrieve}). Tenant-scoped; a REQUESTER viewing someone
 * else's request gets the same 404 as a missing id.
 */
@Singleton
public class RetrieveServiceRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveServiceRequestUseCase.class);

    private final ServiceRequestRepository requestRepository;

    public RetrieveServiceRequestUseCase(ServiceRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    public Mono<ServiceRequestResponse> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Retrieving ServiceRequest by ID: {}", id);

        boolean requester = RequestWorkflow.REQUESTER_ROLE.equals(role);
        return requestRepository.findById(id, organisationId)
                .switchIfEmpty(Mono.error(new ServiceRequestNotFoundException(id)))
                .flatMap(request -> authorize(request, id, executor, requester))
                .map(request -> ServiceRequestMapper.toResponse(request, !requester, Instant.now()));
    }

    private static Mono<ServiceRequest> authorize(ServiceRequest request, UUID id, String executor, boolean requester) {
        if (requester && !request.getRequesterId().equals(UUID.fromString(executor))) {
            return Mono.error(new ServiceRequestNotFoundException(id));
        }
        return Mono.just(request);
    }
}
