package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.CatalogItemResponse;
import com.thinklab.application.mapper.CatalogItemMapper;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.repository.CatalogItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Use Case for the tenant-scoped catalog (BIAN Behavior Qualifier: {@code catalog/retrieve}, collection). A REQUESTER is always shown only
 * the PUBLISHED items, whatever status they ask for.
 */
@Singleton
public class RetrieveCatalogItemsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveCatalogItemsUseCase.class);

    private final CatalogItemRepository catalogRepository;

    public RetrieveCatalogItemsUseCase(CatalogItemRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public Flux<CatalogItemResponse> execute(UUID organisationId, CatalogItemStatus status, String role) {
        log.info("[USE CASE] Retrieving the catalog for organisation: {} status: {} role: {}", organisationId, status, role);

        CatalogItemStatus effective = RequestWorkflow.REQUESTER_ROLE.equals(role) ? CatalogItemStatus.PUBLISHED : status;
        return catalogRepository.findAll(organisationId, effective).map(CatalogItemMapper::toResponse);
    }
}
