package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.ServiceRequestMapper;
import com.thinklab.domain.exception.ServiceRequestAccessDeniedException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a ServiceRequest (BIAN Behavior Qualifier: {@code audit-log/retrieve}); a staff action. */
@Singleton
public class RetrieveServiceRequestAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveServiceRequestAuditLogUseCase.class);

    private final ServiceRequestRepository requestRepository;

    public RetrieveServiceRequestAuditLogUseCase(ServiceRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of ServiceRequest ID: {}", id);

        if (RequestWorkflow.REQUESTER_ROLE.equals(role)) {
            return Mono.error(new ServiceRequestAccessDeniedException("read the audit trail of a request"));
        }
        return requestRepository.findById(id, organisationId)
                .switchIfEmpty(Mono.error(new ServiceRequestNotFoundException(id)))
                .map(sr -> sr.getAuditTrail().stream().map(ServiceRequestMapper::toResponse).collect(Collectors.toList()));
    }
}
