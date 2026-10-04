package com.thinklab.application.usecase;

import com.thinklab.domain.exception.CatalogItemNotFoundException;
import com.thinklab.domain.exception.ServiceRequestAccessDeniedException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogAuditEntry;
import com.thinklab.domain.repository.CatalogItemRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The common shape of every change to the catalog: staff only (ADR-031), tenant-scoped, and one guarded write with its audit entry. */
@Singleton
public class CatalogWorkflow {

    private final CatalogItemRepository catalogRepository;

    public CatalogWorkflow(CatalogItemRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    /** Refuses a REQUESTER: managing the catalog is a staff action. */
    static void requireStaff(String role, String operation) {
        if (RequestWorkflow.REQUESTER_ROLE.equals(role)) {
            throw new ServiceRequestAccessDeniedException(operation);
        }
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<CatalogItem, CatalogAuditEntry> action) {
        return Mono.fromRunnable(() -> requireStaff(role, operation))
                .then(Mono.defer(() -> catalogRepository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new CatalogItemNotFoundException(id)))
                .flatMap(item -> {
                    var statusBefore = item.getStatus();
                    CatalogAuditEntry entry = action.apply(item);
                    return catalogRepository.save(item, statusBefore, entry);
                });
    }
}
