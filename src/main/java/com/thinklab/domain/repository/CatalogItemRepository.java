package com.thinklab.domain.repository;

import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogAuditEntry;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for CatalogItem persistence (IT Service Request Service Domain).
 *
 * <p>ARCHITECTURAL RULE: Partial State Mutations (ADR-002). {@link #create} is the only whole-document write; {@link #save} persists the
 * state a domain operation reached together with its audit entry in one atomic update. There is no {@code deleteById}. Every lookup is
 * tenant-scoped: another organisation's item is simply not found.
 */
public interface CatalogItemRepository {

    /** Inserts the item; a code already taken in the organisation is {@link com.thinklab.domain.exception.DuplicateCatalogItemException}. */
    Mono<CatalogItem> create(CatalogItem item);

    Mono<CatalogItem> findById(UUID id, UUID organisationId);

    Mono<CatalogItem> findByCode(String code, UUID organisationId);

    Flux<CatalogItem> findAll(UUID organisationId, CatalogItemStatus status);

    /**
     * Persists the state the item reached, with its audit entry, only while it is still in {@code expectedStatus} (the status it had when
     * loaded); otherwise {@link com.thinklab.domain.exception.InvalidCatalogItemStatusException} (409, retry).
     */
    Mono<Void> save(CatalogItem item, CatalogItemStatus expectedStatus, CatalogAuditEntry auditEntry);
}
