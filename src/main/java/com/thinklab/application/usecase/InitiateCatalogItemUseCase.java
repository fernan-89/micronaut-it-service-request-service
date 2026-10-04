package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateCatalogItemRequest;
import com.thinklab.application.dto.response.CatalogItemResponse;
import com.thinklab.application.mapper.CatalogItemMapper;
import com.thinklab.domain.exception.DuplicateCatalogItemException;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CatalogItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * Use Case for drafting a new CatalogItem (BIAN Behavior Qualifier: {@code catalog/initiate}). Staff only. The use case checks the code
 * first; the unique {@code (organisationId, code)} index is the atomic backstop for two concurrent creations (ADR-033).
 */
@Singleton
public class InitiateCatalogItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateCatalogItemUseCase.class);

    private final HashServicePort hashServicePort;
    private final CatalogItemRepository catalogRepository;

    public InitiateCatalogItemUseCase(HashServicePort hashServicePort, CatalogItemRepository catalogRepository) {
        this.hashServicePort = hashServicePort;
        this.catalogRepository = catalogRepository;
    }

    public Mono<CatalogItemResponse> execute(UUID organisationId, InitiateCatalogItemRequest request, String executor, String role) {
        log.info("[USE CASE] Drafting CatalogItem [{}] for organisation: {}", request.code(), organisationId);

        return Mono.fromRunnable(() -> CatalogWorkflow.requireStaff(role, "manage the catalog"))
                .then(Mono.defer(() -> catalogRepository.findByCode(request.code().trim().toUpperCase(Locale.ROOT), organisationId)))
                .flatMap(existing -> Mono.<UUID>error(new DuplicateCatalogItemException(existing.getCode())))
                .switchIfEmpty(Mono.defer(() -> hashServicePort.generateSovereignId("catalog-item-creation")))
                .map(sovereignId -> CatalogItemMapper.toDomain(request, sovereignId, organisationId, executor))
                .flatMap(catalogRepository::create)
                .map(CatalogItemMapper::toResponse);
    }
}
