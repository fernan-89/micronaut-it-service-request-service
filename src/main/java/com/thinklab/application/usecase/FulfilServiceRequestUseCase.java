package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.FulfilServiceRequestRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for fulfilling a ServiceRequest (BIAN Behavior Qualifier: {@code control/fulfil}); notes on what was delivered are mandatory. */
@Singleton
public class FulfilServiceRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(FulfilServiceRequestUseCase.class);

    private final RequestWorkflow workflow;

    public FulfilServiceRequestUseCase(RequestWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, FulfilServiceRequestRequest request, String executor, String role) {
        log.info("[USE CASE] Fulfilling ServiceRequest ID: {}", id);

        return workflow.apply(id, organisationId, role, "fulfil a request", sr -> sr.fulfil(request.notes(), executor));
    }
}
