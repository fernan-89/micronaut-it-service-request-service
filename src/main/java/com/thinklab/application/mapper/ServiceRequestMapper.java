package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.CommentResponse;
import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.application.dto.response.SlaResponse;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.RequestAuditEntry;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Static factory mapper for ServiceRequest DTOs and the Domain aggregate. */
public final class ServiceRequestMapper {

    private ServiceRequestMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * @param includeInternal false for a REQUESTER: internal comments are left out
     * @param now             the moment the SLA is judged at (a target still running is breached once it is past due)
     */
    public static ServiceRequestResponse toResponse(ServiceRequest request, boolean includeInternal, Instant now) {
        boolean noSla = request.getStatus() == ServiceRequestStatus.CANCELLED || request.getStatus() == ServiceRequestStatus.REJECTED;
        List<CommentResponse> comments = request.getComments().stream()
                .filter(comment -> includeInternal || !comment.internal())
                .map(ServiceRequestMapper::toResponse)
                .collect(Collectors.toList());
        return new ServiceRequestResponse(request.getId(), request.getOrganisationId(), request.getRequesterId(), request.getCatalogItemId(),
                request.getCatalogItemCode(), request.getCatalogItemName(), request.getAnswers(), request.getStatus().name(), request.getAssigneeId(),
                request.getApprovalRequestId(), noSla ? null : sla(request.getFulfilmentDueAt(), request.getFulfilledAt(), now),
                request.getStartedAt(), request.getFulfilledAt(), request.getFulfilmentNotes(), comments, request.getCreatedAt(), request.getUpdatedAt());
    }

    /** MET or BREACHED once the target was reached (late or not); otherwise PENDING until the due date passes, then BREACHED. */
    static SlaResponse sla(Instant dueAt, Instant doneAt, Instant now) {
        String state;
        if (doneAt != null) {
            state = doneAt.isAfter(dueAt) ? "BREACHED" : "MET";
        } else {
            state = now.isAfter(dueAt) ? "BREACHED" : "PENDING";
        }
        return new SlaResponse(dueAt, state);
    }

    private static CommentResponse toResponse(Comment comment) {
        return new CommentResponse(comment.commentId(), comment.author(), comment.text(), comment.internal(), comment.createdAt());
    }

    public static AuditEntryResponse toResponse(RequestAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(),
                entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
    }
}
