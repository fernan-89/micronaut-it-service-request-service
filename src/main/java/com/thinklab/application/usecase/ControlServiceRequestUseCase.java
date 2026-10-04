package com.thinklab.application.usecase;

import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.RequestAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for the status-only transitions of a ServiceRequest (BIAN Behavior Qualifiers {@code control/start-fulfilment} and
 * {@code control/close}). Fulfilling (which carries notes) and cancelling (which also withdraws a pending approval) have their own use cases.
 */
@Singleton
public class ControlServiceRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlServiceRequestUseCase.class);

    /** One status-only transition: its name, for the refusal message, and what the aggregate does. */
    public enum Action {
        START_FULFILMENT("start fulfilling a request") {
            @Override RequestAuditEntry apply(ServiceRequest request, String executor) { return request.startFulfilment(executor); }
        },
        CLOSE("close a request") {
            @Override RequestAuditEntry apply(ServiceRequest request, String executor) { return request.close(executor); }
        };

        private final String operation;

        Action(String operation) {
            this.operation = operation;
        }

        abstract RequestAuditEntry apply(ServiceRequest request, String executor);
    }

    private final RequestWorkflow workflow;

    public ControlServiceRequestUseCase(RequestWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on ServiceRequest ID: {}", action, id);

        return workflow.apply(id, organisationId, role, action.operation, request -> action.apply(request, executor));
    }
}
