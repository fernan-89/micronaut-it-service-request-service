package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.RequestAuditEntry;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the ServiceRequest Aggregate for MongoDB. */
@Introspected
public class ServiceRequestDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private UUID requesterId;
    private UUID catalogItemId;
    private String catalogItemCode;
    private String catalogItemName;
    private Map<String, String> answers = new LinkedHashMap<>();
    private String status;
    private UUID assigneeId;
    private UUID approvalRequestId;
    private Instant fulfilmentDueAt;
    private Instant startedAt;
    private Instant fulfilledAt;
    private String fulfilmentNotes;
    private List<CommentDocument> comments = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getRequesterId() { return requesterId; }
    public void setRequesterId(UUID requesterId) { this.requesterId = requesterId; }
    public UUID getCatalogItemId() { return catalogItemId; }
    public void setCatalogItemId(UUID catalogItemId) { this.catalogItemId = catalogItemId; }
    public String getCatalogItemCode() { return catalogItemCode; }
    public void setCatalogItemCode(String catalogItemCode) { this.catalogItemCode = catalogItemCode; }
    public String getCatalogItemName() { return catalogItemName; }
    public void setCatalogItemName(String catalogItemName) { this.catalogItemName = catalogItemName; }
    public Map<String, String> getAnswers() { return answers; }
    public void setAnswers(Map<String, String> answers) { this.answers = answers; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getAssigneeId() { return assigneeId; }
    public void setAssigneeId(UUID assigneeId) { this.assigneeId = assigneeId; }
    public UUID getApprovalRequestId() { return approvalRequestId; }
    public void setApprovalRequestId(UUID approvalRequestId) { this.approvalRequestId = approvalRequestId; }
    public Instant getFulfilmentDueAt() { return fulfilmentDueAt; }
    public void setFulfilmentDueAt(Instant fulfilmentDueAt) { this.fulfilmentDueAt = fulfilmentDueAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(Instant fulfilledAt) { this.fulfilledAt = fulfilledAt; }
    public String getFulfilmentNotes() { return fulfilmentNotes; }
    public void setFulfilmentNotes(String fulfilmentNotes) { this.fulfilmentNotes = fulfilmentNotes; }
    public List<CommentDocument> getComments() { return comments; }
    public void setComments(List<CommentDocument> comments) { this.comments = comments; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record CommentDocument(UUID commentId, String author, String text, boolean internal, Instant createdAt) {
        public static CommentDocument fromDomain(Comment comment) {
            return new CommentDocument(comment.commentId(), comment.author(), comment.text(), comment.internal(), comment.createdAt());
        }

        Comment toDomain() { return new Comment(commentId, author, text, internal, createdAt); }
    }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(RequestAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(),
                    entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        RequestAuditEntry toDomain() {
            return new RequestAuditEntry(occurredAt, action, executor, fromStatus != null ? ServiceRequestStatus.valueOf(fromStatus) : null,
                    ServiceRequestStatus.valueOf(toStatus), detail);
        }
    }

    public static final class ServiceRequestPersistenceMapper {

        private ServiceRequestPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static ServiceRequestDocument toDocument(ServiceRequest request) {
            ServiceRequestDocument doc = new ServiceRequestDocument();
            doc.setId(request.getId());
            doc.setOrganisationId(request.getOrganisationId());
            doc.setRequesterId(request.getRequesterId());
            doc.setCatalogItemId(request.getCatalogItemId());
            doc.setCatalogItemCode(request.getCatalogItemCode());
            doc.setCatalogItemName(request.getCatalogItemName());
            doc.setAnswers(new LinkedHashMap<>(request.getAnswers()));
            doc.setStatus(request.getStatus().name());
            doc.setAssigneeId(request.getAssigneeId());
            doc.setApprovalRequestId(request.getApprovalRequestId());
            doc.setFulfilmentDueAt(request.getFulfilmentDueAt());
            doc.setStartedAt(request.getStartedAt());
            doc.setFulfilledAt(request.getFulfilledAt());
            doc.setFulfilmentNotes(request.getFulfilmentNotes());
            doc.setComments(request.getComments().stream().map(CommentDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            doc.setCreatedAt(request.getCreatedAt());
            doc.setUpdatedAt(request.getUpdatedAt());
            doc.setAuditTrail(request.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static ServiceRequest toDomain(ServiceRequestDocument doc) {
            return ServiceRequest.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getRequesterId(), doc.getCatalogItemId(), doc.getCatalogItemCode(),
                    doc.getCatalogItemName(), doc.getAnswers(), ServiceRequestStatus.valueOf(doc.getStatus()), doc.getAssigneeId(), doc.getApprovalRequestId(),
                    doc.getFulfilmentDueAt(), doc.getStartedAt(), doc.getFulfilledAt(), doc.getFulfilmentNotes(),
                    doc.getComments().stream().map(CommentDocument::toDomain).collect(Collectors.toList()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
