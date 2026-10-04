package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.AssignServiceRequestRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for naming who fulfils a ServiceRequest (BIAN Behavior Qualifier: {@code assignment/update}). Staff only. */
@Singleton
public class AssignServiceRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(AssignServiceRequestUseCase.class);

    private final RequestWorkflow workflow;

    public AssignServiceRequestUseCase(RequestWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, AssignServiceRequestRequest request, String executor, String role) {
        log.info("[USE CASE] Assigning ServiceRequest ID: {} to {}", id, request.assigneeId());

        return workflow.apply(id, organisationId, role, "assign a request", sr -> sr.assign(request.assigneeId(), executor));
    }
}
