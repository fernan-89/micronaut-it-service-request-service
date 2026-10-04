package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.CatalogItemMapper;
import com.thinklab.domain.exception.CatalogItemNotFoundException;
import com.thinklab.domain.repository.CatalogItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a CatalogItem (BIAN Behavior Qualifier: {@code catalog/audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveCatalogAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveCatalogAuditLogUseCase.class);

    private final CatalogItemRepository catalogRepository;

    public RetrieveCatalogAuditLogUseCase(CatalogItemRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of CatalogItem ID: {}", id);

        return Mono.fromRunnable(() -> CatalogWorkflow.requireStaff(role, "read the audit trail of a catalog item"))
                .then(Mono.defer(() -> catalogRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new CatalogItemNotFoundException(id)))
                .map(item -> item.getAuditTrail().stream().map(CatalogItemMapper::toResponse).collect(Collectors.toList()));
    }
}
