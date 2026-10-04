package com.thinklab.application.usecase;

import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for the lifecycle of a CatalogItem (BIAN Behavior Qualifiers {@code catalog/control/publish} and {@code retire}). Staff only. */
@Singleton
public class ControlCatalogItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlCatalogItemUseCase.class);

    public enum Action {
        PUBLISH {
            @Override CatalogAuditEntry apply(CatalogItem item, String executor) { return item.publish(executor); }
        },
        RETIRE {
            @Override CatalogAuditEntry apply(CatalogItem item, String executor) { return item.retire(executor); }
        };

        abstract CatalogAuditEntry apply(CatalogItem item, String executor);
    }

    private final CatalogWorkflow workflow;

    public ControlCatalogItemUseCase(CatalogWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on CatalogItem ID: {}", action, id);

        return workflow.apply(id, organisationId, role, "manage the catalog", item -> action.apply(item, executor));
    }
}
