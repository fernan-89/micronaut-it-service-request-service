package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidCatalogItemStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Core Domain Model representing the CatalogItem Aggregate Root (BIAN Service Domain: {@code it-service-request}): one thing a person can
 * ask IT for ("New laptop", "Access to the VPN"), defined per organisation.
 *
 * <p><b>What an item decides (ADR-030):</b> the questions a requester must answer ({@link Field}s), how long fulfilment may take
 * ({@code fulfilmentTargetHours}, the SLA of every request made from it) and whether it needs approval first (an optional
 * {@code approvalPolicyId} on {@code workflow-approval-service}, which may be a chain of stages). A request snapshots all of this when it
 * is made, so editing the catalog never changes a request in flight.
 *
 * <p><b>Lifecycle:</b> {@code DRAFT -> PUBLISHED -> RETIRED}. An item is editable only while DRAFT (a published item is a promise to the
 * people who request it), only a PUBLISHED item can be requested, and a RETIRED one keeps serving requests already made but is no longer
 * offered. There is no {@code DELETE}.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class CatalogItem {

    public static final int MAX_FIELDS = 20;
    public static final int MAX_TARGET_HOURS = 24 * 90;

    private final UUID id;
    private final UUID organisationId;
    private final String code;
    private String name;
    private String description;
    private String category;
    private List<Field> fields;
    private int fulfilmentTargetHours;
    private UUID approvalPolicyId;
    private CatalogItemStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<CatalogAuditEntry> auditTrail;

    private CatalogItem(UUID id, UUID organisationId, String code, String name, String description, String category, List<Field> fields,
                        int fulfilmentTargetHours, UUID approvalPolicyId, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.category = category;
        this.fields = List.copyOf(fields);
        this.fulfilmentTargetHours = fulfilmentTargetHours;
        this.approvalPolicyId = approvalPolicyId;
        this.status = CatalogItemStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new CatalogAuditEntry(this.createdAt, "INITIATED", executor, null, CatalogItemStatus.DRAFT, "Catalog item drafted."));
    }

    private CatalogItem(UUID id, UUID organisationId, String code, String name, String description, String category, List<Field> fields,
                        int fulfilmentTargetHours, UUID approvalPolicyId, CatalogItemStatus status, Instant createdAt, Instant updatedAt,
                        List<CatalogAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.category = category;
        this.fields = fields != null ? List.copyOf(fields) : List.of();
        this.fulfilmentTargetHours = fulfilmentTargetHours;
        this.approvalPolicyId = approvalPolicyId;
        this.status = status != null ? status : CatalogItemStatus.DRAFT;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static CatalogItem createNew(UUID id, UUID organisationId, String code, String name, String description, String category,
                                        List<Field> fields, int fulfilmentTargetHours, UUID approvalPolicyId, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for CatalogItem creation.");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code is mandatory for CatalogItem creation.");
        }
        validate(name, fields, fulfilmentTargetHours);
        requireExecutor(executor);
        return new CatalogItem(id, organisationId, code.trim().toUpperCase(Locale.ROOT), name, description, category, fields, fulfilmentTargetHours,
                approvalPolicyId, executor);
    }

    public static CatalogItem reconstitute(UUID id, UUID organisationId, String code, String name, String description, String category,
                                           List<Field> fields, int fulfilmentTargetHours, UUID approvalPolicyId, CatalogItemStatus status,
                                           Instant createdAt, Instant updatedAt, List<CatalogAuditEntry> auditTrail) {
        if (id == null || organisationId == null || code == null || name == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Code and Name are mandatory to reconstitute a CatalogItem.");
        }
        return new CatalogItem(id, organisationId, code, name, description, category, fields, fulfilmentTargetHours, approvalPolicyId, status,
                createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code update}. Only while DRAFT: a published item is a promise to the people who request it. */
    public CatalogAuditEntry updateDetails(String newName, String newDescription, String newCategory, List<Field> newFields,
                                           int newFulfilmentTargetHours, UUID newApprovalPolicyId, String executor) {
        requireStatus(CatalogItemStatus.DRAFT);
        validate(newName, newFields, newFulfilmentTargetHours);
        this.name = newName;
        this.description = newDescription;
        this.category = newCategory;
        this.fields = List.copyOf(newFields);
        this.fulfilmentTargetHours = newFulfilmentTargetHours;
        this.approvalPolicyId = newApprovalPolicyId;
        return record("UPDATED", executor, "Details updated.");
    }

    /** Behavior Qualifier: {@code control/publish}. DRAFT -&gt; PUBLISHED: from now on it can be requested. */
    public CatalogAuditEntry publish(String executor) {
        requireStatus(CatalogItemStatus.DRAFT);
        return transition(CatalogItemStatus.PUBLISHED, "PUBLISHED", executor, "Published: it can now be requested.");
    }

    /** Behavior Qualifier: {@code control/retire}. PUBLISHED -&gt; RETIRED (terminal): no longer offered. */
    public CatalogAuditEntry retire(String executor) {
        requireStatus(CatalogItemStatus.PUBLISHED);
        return transition(CatalogItemStatus.RETIRED, "RETIRED", executor, "Retired: it is no longer offered.");
    }

    /** A request can only be made from a PUBLISHED item. */
    public void requireRequestable() {
        if (status != CatalogItemStatus.PUBLISHED) {
            throw new InvalidCatalogItemStatusException(String.format(
                    "Catalog item [%s] is %s: only a PUBLISHED item can be requested.", code, status));
        }
    }

    // --- Internal helpers ---

    private CatalogAuditEntry transition(CatalogItemStatus newStatus, String action, String executor, String detail) {
        requireExecutor(executor);
        CatalogItemStatus previous = this.status;
        this.status = newStatus;
        this.updatedAt = Instant.now();
        CatalogAuditEntry entry = new CatalogAuditEntry(this.updatedAt, action, executor, previous, newStatus, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private CatalogAuditEntry record(String action, String executor, String detail) {
        requireExecutor(executor);
        this.updatedAt = Instant.now();
        CatalogAuditEntry entry = new CatalogAuditEntry(this.updatedAt, action, executor, this.status, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(CatalogItemStatus... allowed) {
        if (Arrays.asList(allowed).contains(this.status)) {
            return;
        }
        throw new InvalidCatalogItemStatusException(String.format(
                "Illegal transition: CatalogItem is [%s], expected one of %s.", this.status, Arrays.toString(allowed)));
    }

    private static void validate(String name, List<Field> fields, int fulfilmentTargetHours) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is mandatory for a CatalogItem.");
        }
        if (fields == null) {
            throw new IllegalArgumentException("Fields are mandatory for a CatalogItem (an empty list is fine).");
        }
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("A catalog item can ask at most " + MAX_FIELDS + " questions.");
        }
        Set<String> keys = new HashSet<>();
        for (Field field : fields) {
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException("Two questions share the key [" + field.key() + "].");
            }
        }
        if (fulfilmentTargetHours < 1 || fulfilmentTargetHours > MAX_TARGET_HOURS) {
            throw new IllegalArgumentException("The fulfilment target must be between 1 and " + MAX_TARGET_HOURS + " hours.");
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable CatalogItem mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public List<Field> getFields() { return Collections.unmodifiableList(fields); }
    public int getFulfilmentTargetHours() { return fulfilmentTargetHours; }
    public UUID getApprovalPolicyId() { return approvalPolicyId; }
    public CatalogItemStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<CatalogAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    /** {@code DRAFT -> PUBLISHED -> RETIRED}. */
    public enum CatalogItemStatus { DRAFT, PUBLISHED, RETIRED }

    /** One question a requester answers: a short key, a label for the person, and whether an answer is mandatory. */
    public record Field(String key, String label, boolean required) {
        public Field {
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,39}")) {
                throw new IllegalArgumentException("A question key is a letter followed by up to 39 letters, digits or underscores.");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("A question needs a label.");
            }
        }
    }

    /** Immutable forensic ledger entry, mirroring the platform's established audit-trail pattern. */
    public record CatalogAuditEntry(Instant occurredAt, String action, String executor,
                                    CatalogItemStatus fromStatus, CatalogItemStatus toStatus, String detail) {}
}
