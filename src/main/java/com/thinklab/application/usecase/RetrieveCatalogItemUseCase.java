package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.CatalogItemResponse;
import com.thinklab.application.mapper.CatalogItemMapper;
import com.thinklab.domain.exception.CatalogItemNotFoundException;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.repository.CatalogItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for retrieving one CatalogItem (BIAN Behavior Qualifier: {@code catalog/retrieve}). Tenant-scoped; a REQUESTER sees only what
 * is on offer, so a draft or retired item answers 404 for them (the catalog being prepared is not theirs to see).
 */
@Singleton
public class RetrieveCatalogItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveCatalogItemUseCase.class);

    private final CatalogItemRepository catalogRepository;

    public RetrieveCatalogItemUseCase(CatalogItemRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public Mono<CatalogItemResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving CatalogItem by ID: {}", id);

        boolean requester = RequestWorkflow.REQUESTER_ROLE.equals(role);
        return catalogRepository.findById(id, organisationId)
                .filter(item -> !requester || item.getStatus() == CatalogItemStatus.PUBLISHED)
                .switchIfEmpty(Mono.error(new CatalogItemNotFoundException(id)))
                .map(CatalogItemMapper::toResponse);
    }
}
