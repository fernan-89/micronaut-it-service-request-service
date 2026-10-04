package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.model.CatalogItem.Field;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Core Domain Model representing the ServiceRequest Aggregate Root (BIAN Service Domain: {@code it-service-request}): a person asking IT
 * for something from the catalog ("a new laptop", "VPN access"), scoped to an Organisation, made by staff or, self-service, by the
 * {@code REQUESTER} role (ADR-031).
 *
 * <p><b>It snapshots its catalog item (ADR-030):</b> the item's code and name, and the fulfilment due date computed from the item's
 * target at the moment of the request. Editing or retiring the item later never changes a request in flight. The answers to the item's
 * questions are checked against the item's fields when the request is made: an unknown key, a missing mandatory answer or an over-long
 * answer is refused.
 *
 * <p><b>Approval is somebody else's job (ADR-032):</b> an item with an approval policy makes the request start in
 * {@code PENDING_APPROVAL} and carry the id of the approval request filed on {@code workflow-approval-service} (which may be a chain of
 * stages); the decision comes back through {@link #approve} or {@link #reject}. An item without one starts in {@code SUBMITTED}.
 *
 * <p><b>SLA is computed, not scheduled (ADR-034):</b> the fulfilment due date is stored; whether it was met or breached is answered when the
 * request is read. The clock starts at the request, so time waiting for approval counts.
 *
 * <p><b>Forensic Audit Ledger:</b> every mutation appends an immutable {@link RequestAuditEntry}.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class ServiceRequest {

    public static final int MAX_ANSWER_LENGTH = 500;

    private final UUID id;
    private final UUID organisationId;
    private final UUID requesterId;
    private final UUID catalogItemId;
    private final String catalogItemCode;
    private final String catalogItemName;
    private Map<String, String> answers;
    private ServiceRequestStatus status;
    private UUID assigneeId;
    private UUID approvalRequestId;
    private final Instant fulfilmentDueAt;
    private Instant startedAt;
    private Instant fulfilledAt;
    private String fulfilmentNotes;
    private String returnReason;
    private final List<Comment> comments;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<RequestAuditEntry> auditTrail;

    private ServiceRequest(UUID id, UUID organisationId, UUID requesterId, CatalogItem item, Map<String, String> answers,
                           UUID approvalRequestId, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.requesterId = requesterId;
        this.catalogItemId = item.getId();
        this.catalogItemCode = item.getCode();
        this.catalogItemName = item.getName();
        this.answers = new LinkedHashMap<>(answers);
        this.approvalRequestId = approvalRequestId;
        this.status = approvalRequestId != null ? ServiceRequestStatus.PENDING_APPROVAL : ServiceRequestStatus.SUBMITTED;
        this.comments = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.fulfilmentDueAt = this.createdAt.plus(Duration.ofHours(item.getFulfilmentTargetHours()));
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new RequestAuditEntry(this.createdAt, "INITIATED", executor, null, this.status,
                approvalRequestId != null ? "Requested " + catalogItemCode + "; waiting for approval." : "Requested " + catalogItemCode + "."));
    }

    private ServiceRequest(UUID id, UUID organisationId, UUID requesterId, UUID catalogItemId, String catalogItemCode, String catalogItemName,
                           Map<String, String> answers, ServiceRequestStatus status, UUID assigneeId, UUID approvalRequestId,
                           Instant fulfilmentDueAt, Instant startedAt, Instant fulfilledAt, String fulfilmentNotes, String returnReason, List<Comment> comments,
                           Instant createdAt, Instant updatedAt, List<RequestAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.requesterId = requesterId;
        this.catalogItemId = catalogItemId;
        this.catalogItemCode = catalogItemCode;
        this.catalogItemName = catalogItemName;
        this.answers = answers != null ? new LinkedHashMap<>(answers) : new LinkedHashMap<>();
        this.status = status != null ? status : ServiceRequestStatus.SUBMITTED;
        this.assigneeId = assigneeId;
        this.approvalRequestId = approvalRequestId;
        this.fulfilmentDueAt = fulfilmentDueAt;
        this.startedAt = startedAt;
        this.fulfilledAt = fulfilledAt;
        this.fulfilmentNotes = fulfilmentNotes;
        this.returnReason = returnReason;
        this.comments = comments != null ? new ArrayList<>(comments) : new ArrayList<>();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /**
     * Makes a request from a PUBLISHED catalog item. {@code approvalRequestId} must be present exactly when the item has an approval
     * policy: the approval request is filed first, because a request that needs approval must never exist without one.
     */
    public static ServiceRequest createNew(UUID id, UUID organisationId, UUID requesterId, CatalogItem item, Map<String, String> answers,
                                           UUID approvalRequestId, String executor) {
        if (id == null || organisationId == null || requesterId == null || item == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Requester ID and the catalog item are mandatory for ServiceRequest creation.");
        }
        item.requireRequestable();
        if (!organisationId.equals(item.getOrganisationId())) {
            throw new IllegalArgumentException("The catalog item belongs to another organisation.");
        }
        if ((item.getApprovalPolicyId() != null) != (approvalRequestId != null)) {
            throw new IllegalArgumentException("An approval request is needed exactly when the catalog item has an approval policy.");
        }
        Map<String, String> given = answers == null ? Map.of() : answers;
        validateAnswers(item.getFields(), given);
        requireExecutor(executor);
        return new ServiceRequest(id, organisationId, requesterId, item, given, approvalRequestId, executor);
    }

    public static ServiceRequest reconstitute(UUID id, UUID organisationId, UUID requesterId, UUID catalogItemId, String catalogItemCode,
                                              String catalogItemName, Map<String, String> answers, ServiceRequestStatus status, UUID assigneeId,
                                              UUID approvalRequestId, Instant fulfilmentDueAt, Instant startedAt, Instant fulfilledAt,
                                              String fulfilmentNotes, String returnReason, List<Comment> comments, Instant createdAt,
                                              Instant updatedAt, List<RequestAuditEntry> auditTrail) {
        if (id == null || organisationId == null || requesterId == null || catalogItemId == null || catalogItemCode == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Requester ID and the catalog item are mandatory to reconstitute a ServiceRequest.");
        }
        return new ServiceRequest(id, organisationId, requesterId, catalogItemId, catalogItemCode, catalogItemName, answers, status, assigneeId,
                approvalRequestId, fulfilmentDueAt, startedAt, fulfilledAt, fulfilmentNotes, returnReason, comments, createdAt, updatedAt, auditTrail);
    }

    /** Checks the answers against the item's questions without making anything: unknown key, missing mandatory answer, over-long answer. */
    public static void checkAnswers(CatalogItem item, Map<String, String> answers) {
        validateAnswers(item.getFields(), answers == null ? Map.of() : answers);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code assignment/update}. Names who fulfils the request; not a status change. */
    public RequestAuditEntry assign(UUID newAssigneeId, String executor) {
        requireNotTerminal("assign");
        Objects.requireNonNull(newAssigneeId, "Assignee is mandatory to assign a ServiceRequest.");
        this.assigneeId = newAssigneeId;
        return record("ASSIGNED", executor, "Assigned to [" + newAssigneeId + "].");
    }

    /** The approval was granted (every stage of the chain, if there is one). PENDING_APPROVAL -&gt; APPROVED. */
    public RequestAuditEntry approve(String executor) {
        requireStatus(ServiceRequestStatus.PENDING_APPROVAL);
        return transition(ServiceRequestStatus.APPROVED, "APPROVED", executor, "Approved.");
    }

    /** The approval was refused. PENDING_APPROVAL -&gt; REJECTED (terminal). */
    public RequestAuditEntry reject(String executor) {
        requireStatus(ServiceRequestStatus.PENDING_APPROVAL);
        return transition(ServiceRequestStatus.REJECTED, "REJECTED", executor, "Rejected by the approvers.");
    }

    /** The approvers sent it back with what to fix (an approval outcome of RETURN). PENDING_APPROVAL -&gt; RETURNED; the requester edits it and resubmits. */
    public RequestAuditEntry returnForChanges(String reason, String executor) {
        requireStatus(ServiceRequestStatus.PENDING_APPROVAL);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason saying what to fix is mandatory to return a ServiceRequest.");
        }
        this.returnReason = reason;
        return transition(ServiceRequestStatus.RETURNED, "RETURNED", executor, "Returned for changes: " + reason);
    }

    /**
     * Behavior Qualifier: {@code control/resubmit}. RETURNED -&gt; PENDING_APPROVAL (or SUBMITTED if the item needs no approval): the answers
     * are replaced (checked against the item again) and a NEW approval request starts from stage one; the fulfilment due date does not move,
     * so the clock keeps running (ADR-034). {@code newApprovalRequestId} is needed exactly when the item has an approval policy.
     */
    public RequestAuditEntry resubmit(CatalogItem item, Map<String, String> newAnswers, UUID newApprovalRequestId, String executor) {
        requireStatus(ServiceRequestStatus.RETURNED);
        Objects.requireNonNull(item, "The catalog item is mandatory to resubmit a ServiceRequest.");
        item.requireRequestable();
        if (!item.getId().equals(catalogItemId)) {
            throw new IllegalArgumentException("The catalog item is not the one this request was made from.");
        }
        if ((item.getApprovalPolicyId() != null) != (newApprovalRequestId != null)) {
            throw new IllegalArgumentException("An approval request is needed exactly when the catalog item has an approval policy.");
        }
        Map<String, String> given = newAnswers == null ? Map.of() : newAnswers;
        validateAnswers(item.getFields(), given);
        this.answers = new LinkedHashMap<>(given);
        this.approvalRequestId = newApprovalRequestId;
        this.returnReason = null;
        return transition(newApprovalRequestId != null ? ServiceRequestStatus.PENDING_APPROVAL : ServiceRequestStatus.SUBMITTED, "RESUBMITTED", executor,
                "Edited and resubmitted" + (newApprovalRequestId != null ? "; waiting for approval again." : "."));
    }

    /** Behavior Qualifier: {@code control/start-fulfilment}. SUBMITTED or APPROVED -&gt; IN_FULFILMENT. */
    public RequestAuditEntry startFulfilment(String executor) {
        requireStatus(ServiceRequestStatus.SUBMITTED, ServiceRequestStatus.APPROVED);
        this.startedAt = Instant.now();
        return transition(ServiceRequestStatus.IN_FULFILMENT, "FULFILMENT_STARTED", executor, "Fulfilment started.");
    }

    /** Behavior Qualifier: {@code control/fulfil}. IN_FULFILMENT -&gt; FULFILLED; notes on what was delivered are mandatory. */
    public RequestAuditEntry fulfil(String notes, String executor) {
        requireStatus(ServiceRequestStatus.IN_FULFILMENT);
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException("Notes on what was delivered are mandatory to fulfil a ServiceRequest.");
        }
        this.fulfilmentNotes = notes;
        this.fulfilledAt = Instant.now();
        return transition(ServiceRequestStatus.FULFILLED, "FULFILLED", executor, "Fulfilled.");
    }

    /** Behavior Qualifier: {@code control/close}. FULFILLED -&gt; CLOSED (terminal). */
    public RequestAuditEntry close(String executor) {
        requireStatus(ServiceRequestStatus.FULFILLED);
        return transition(ServiceRequestStatus.CLOSED, "CLOSED", executor, "Closed.");
    }

    /** Behavior Qualifier: {@code control/cancel} (terminal, replaces DELETE). Before the request is fulfilled. */
    public RequestAuditEntry cancel(String executor) {
        requireStatus(ServiceRequestStatus.SUBMITTED, ServiceRequestStatus.PENDING_APPROVAL, ServiceRequestStatus.RETURNED, ServiceRequestStatus.APPROVED, ServiceRequestStatus.IN_FULFILMENT);
        return transition(ServiceRequestStatus.CANCELLED, "CANCELLED", executor, "Cancelled.");
    }

    /** Behavior Qualifier: {@code comment/initiate}. Not available once the request is finished (closed, cancelled or rejected). */
    public RequestAuditEntry addComment(Comment comment, String executor) {
        requireNotTerminal("comment on");
        Objects.requireNonNull(comment, "comment is mandatory.");
        this.comments.add(comment);
        return record("COMMENT_ADDED", executor, comment.internal() ? "Internal comment added." : "Comment added.");
    }

    // --- Internal helpers ---

    private RequestAuditEntry transition(ServiceRequestStatus newStatus, String action, String executor, String detail) {
        requireExecutor(executor);
        ServiceRequestStatus previous = this.status;
        this.status = newStatus;
        this.updatedAt = Instant.now();
        RequestAuditEntry entry = new RequestAuditEntry(this.updatedAt, action, executor, previous, newStatus, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private RequestAuditEntry record(String action, String executor, String detail) {
        requireExecutor(executor);
        this.updatedAt = Instant.now();
        RequestAuditEntry entry = new RequestAuditEntry(this.updatedAt, action, executor, this.status, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(ServiceRequestStatus... allowed) {
        if (Arrays.asList(allowed).contains(this.status)) {
            return;
        }
        throw new InvalidServiceRequestStatusException(String.format(
                "Illegal transition: ServiceRequest is [%s], expected one of %s.", this.status, Arrays.toString(allowed)));
    }

    private void requireNotTerminal(String operation) {
        if (this.status == ServiceRequestStatus.CLOSED || this.status == ServiceRequestStatus.CANCELLED || this.status == ServiceRequestStatus.REJECTED) {
            throw new InvalidServiceRequestStatusException(String.format(
                    "Compliance Violation: cannot %s a %s ServiceRequest; the lifecycle is terminal.", operation, this.status));
        }
    }

    private static void validateAnswers(List<Field> fields, Map<String, String> answers) {
        for (String key : answers.keySet()) {
            if (fields.stream().noneMatch(field -> field.key().equals(key))) {
                throw new IllegalArgumentException("The catalog item does not ask [" + key + "].");
            }
        }
        for (Field field : fields) {
            String answer = answers.get(field.key());
            if (field.required() && (answer == null || answer.isBlank())) {
                throw new IllegalArgumentException("An answer is required for [" + field.key() + "].");
            }
            if (answer != null && answer.length() > MAX_ANSWER_LENGTH) {
                throw new IllegalArgumentException("The answer to [" + field.key() + "] is longer than " + MAX_ANSWER_LENGTH + " characters.");
            }
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable ServiceRequest mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getCatalogItemId() { return catalogItemId; }
    public String getCatalogItemCode() { return catalogItemCode; }
    public String getCatalogItemName() { return catalogItemName; }
    public Map<String, String> getAnswers() { return Collections.unmodifiableMap(answers); }
    public ServiceRequestStatus getStatus() { return status; }
    public UUID getAssigneeId() { return assigneeId; }
    public UUID getApprovalRequestId() { return approvalRequestId; }
    public Instant getFulfilmentDueAt() { return fulfilmentDueAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFulfilledAt() { return fulfilledAt; }
    public String getFulfilmentNotes() { return fulfilmentNotes; }
    public String getReturnReason() { return returnReason; }
    public List<Comment> getComments() { return Collections.unmodifiableList(comments); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<RequestAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    /**
     * <pre>
     * SUBMITTED (no approval needed) ------------------+
     * PENDING_APPROVAL -&gt; APPROVED ---------------------+-&gt; IN_FULFILMENT -&gt; FULFILLED -&gt; CLOSED (terminal)
     *        |
     *        +-&gt; REJECTED (terminal)
     * SUBMITTED, PENDING_APPROVAL, RETURNED, APPROVED, IN_FULFILMENT -&gt; CANCELLED (terminal)
     * </pre>
     */
    public enum ServiceRequestStatus { SUBMITTED, PENDING_APPROVAL, APPROVED, REJECTED, RETURNED, IN_FULFILMENT, FULFILLED, CLOSED, CANCELLED }

    /** Immutable forensic ledger entry, mirroring the platform's established audit-trail pattern. */
    public record RequestAuditEntry(Instant occurredAt, String action, String executor,
                                    ServiceRequestStatus fromStatus, ServiceRequestStatus toStatus, String detail) {}

    /** @param internal invisible to a REQUESTER-scoped read (application-layer filtering). */
    public record Comment(UUID commentId, String author, String text, boolean internal, Instant createdAt) {
        public Comment {
            Objects.requireNonNull(commentId, "commentId cannot be null.");
            if (author == null || author.isBlank()) {
                throw new IllegalArgumentException("Comment author cannot be blank.");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Comment text cannot be blank.");
            }
            createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }
}
