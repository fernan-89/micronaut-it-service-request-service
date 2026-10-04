package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateCatalogItemRequest;
import com.thinklab.application.mapper.CatalogItemMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for updating a DRAFT CatalogItem (BIAN Behavior Qualifier: {@code catalog/update}). Staff only. */
@Singleton
public class UpdateCatalogItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateCatalogItemUseCase.class);

    private final CatalogWorkflow workflow;

    public UpdateCatalogItemUseCase(CatalogWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdateCatalogItemRequest request, String executor, String role) {
        log.info("[USE CASE] Updating CatalogItem ID: {}", id);

        return workflow.apply(id, organisationId, role, "manage the catalog",
                item -> item.updateDetails(request.name(), request.description(), request.category(), CatalogItemMapper.toFields(request.fields()),
                        request.fulfilmentTargetHours(), request.approvalPolicyId(), executor));
    }
}
