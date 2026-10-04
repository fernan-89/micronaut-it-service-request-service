package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateCommentRequest;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.ServiceRequestRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for adding a Comment to a ServiceRequest (BIAN Behavior Qualifier: {@code comment/initiate}). A REQUESTER can comment only on
 * their own request (anyone else's is a 404) and can never author an internal note: the field is forced to {@code false} for that role.
 */
@Singleton
public class InitiateCommentUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateCommentUseCase.class);

    private final HashServicePort hashServicePort;
    private final ServiceRequestRepository requestRepository;

    public InitiateCommentUseCase(HashServicePort hashServicePort, ServiceRequestRepository requestRepository) {
        this.hashServicePort = hashServicePort;
        this.requestRepository = requestRepository;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, InitiateCommentRequest request, String executor, String role) {
        log.info("[USE CASE] Adding a comment to ServiceRequest ID: {}", id);

        boolean requester = RequestWorkflow.REQUESTER_ROLE.equals(role);
        boolean internal = !requester && request.internal();
        return requestRepository.findById(id, organisationId)
                .filter(sr -> !requester || sr.getRequesterId().equals(UUID.fromString(executor)))
                .switchIfEmpty(Mono.error(new ServiceRequestNotFoundException(id)))
                .flatMap(sr -> hashServicePort.generateSovereignId("service-request-comment-creation")
                        .flatMap(commentId -> {
                            Comment comment = new Comment(commentId, executor, request.text(), internal, Instant.now());
                            var entry = sr.addComment(comment, executor);
                            return requestRepository.addComment(id, organisationId, comment, entry);
                        }));
    }
}
