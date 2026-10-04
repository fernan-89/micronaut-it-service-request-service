package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.FieldRequest;
import com.thinklab.application.dto.request.InitiateCatalogItemRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.CatalogItemResponse;
import com.thinklab.application.dto.response.FieldResponse;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogAuditEntry;
import com.thinklab.domain.model.CatalogItem.Field;

import java.util.List;
import java.util.UUID;

/** Static factory mapper for CatalogItem DTOs and the Domain aggregate. */
public final class CatalogItemMapper {

    private CatalogItemMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static CatalogItem toDomain(InitiateCatalogItemRequest request, UUID sovereignId, UUID organisationId, String executor) {
        return CatalogItem.createNew(sovereignId, organisationId, request.code(), request.name(), request.description(), request.category(),
                toFields(request.fields()), request.fulfilmentTargetHours(), request.approvalPolicyId(), executor);
    }

    public static List<Field> toFields(List<FieldRequest> fields) {
        return fields.stream().map(field -> new Field(field.key(), field.label(), field.required())).toList();
    }

    public static CatalogItemResponse toResponse(CatalogItem item) {
        return new CatalogItemResponse(item.getId(), item.getOrganisationId(), item.getCode(), item.getName(), item.getDescription(), item.getCategory(),
                item.getFields().stream().map(field -> new FieldResponse(field.key(), field.label(), field.required())).toList(),
                item.getFulfilmentTargetHours(), item.getApprovalPolicyId(), item.getStatus().name(), item.getCreatedAt(), item.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(CatalogAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(),
                entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
    }
}
