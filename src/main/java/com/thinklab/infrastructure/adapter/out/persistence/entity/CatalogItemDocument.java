package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogAuditEntry;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.model.CatalogItem.Field;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the CatalogItem Aggregate for MongoDB. */
@Introspected
public class CatalogItemDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String code;
    private String name;
    private String description;
    private String category;
    private List<FieldDocument> fields = new ArrayList<>();
    private int fulfilmentTargetHours;
    private UUID approvalPolicyId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<FieldDocument> getFields() { return fields; }
    public void setFields(List<FieldDocument> fields) { this.fields = fields; }
    public int getFulfilmentTargetHours() { return fulfilmentTargetHours; }
    public void setFulfilmentTargetHours(int fulfilmentTargetHours) { this.fulfilmentTargetHours = fulfilmentTargetHours; }
    public UUID getApprovalPolicyId() { return approvalPolicyId; }
    public void setApprovalPolicyId(UUID approvalPolicyId) { this.approvalPolicyId = approvalPolicyId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record FieldDocument(String key, String label, boolean required) {
        public static FieldDocument fromDomain(Field field) { return new FieldDocument(field.key(), field.label(), field.required()); }

        Field toDomain() { return new Field(key, label, required); }
    }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(CatalogAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(),
                    entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        CatalogAuditEntry toDomain() {
            return new CatalogAuditEntry(occurredAt, action, executor, fromStatus != null ? CatalogItemStatus.valueOf(fromStatus) : null,
                    CatalogItemStatus.valueOf(toStatus), detail);
        }
    }

    public static final class CatalogItemPersistenceMapper {

        private CatalogItemPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static CatalogItemDocument toDocument(CatalogItem item) {
            CatalogItemDocument doc = new CatalogItemDocument();
            doc.setId(item.getId());
            doc.setOrganisationId(item.getOrganisationId());
            doc.setCode(item.getCode());
            doc.setName(item.getName());
            doc.setDescription(item.getDescription());
            doc.setCategory(item.getCategory());
            doc.setFields(item.getFields().stream().map(FieldDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            doc.setFulfilmentTargetHours(item.getFulfilmentTargetHours());
            doc.setApprovalPolicyId(item.getApprovalPolicyId());
            doc.setStatus(item.getStatus().name());
            doc.setCreatedAt(item.getCreatedAt());
            doc.setUpdatedAt(item.getUpdatedAt());
            doc.setAuditTrail(item.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static CatalogItem toDomain(CatalogItemDocument doc) {
            return CatalogItem.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getCode(), doc.getName(), doc.getDescription(), doc.getCategory(),
                    doc.getFields().stream().map(FieldDocument::toDomain).collect(Collectors.toList()), doc.getFulfilmentTargetHours(),
                    doc.getApprovalPolicyId(), CatalogItemStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
