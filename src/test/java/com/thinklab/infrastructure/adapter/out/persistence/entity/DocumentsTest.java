package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CatalogItemDocument.CatalogItemPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ServiceRequestDocument.ServiceRequestPersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentsTest {

    private final UUID org = UUID.randomUUID();

    @Test
    @DisplayName("a catalog item survives storage: questions, policy, status and the audit trail")
    void catalogRoundTrip() {
        UUID policy = UUID.randomUUID();
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), org, "LAPTOP", "New laptop", "d", "HARDWARE",
                List.of(new Field("model", "Which model?", true), new Field("notes", "More?", false)), 72, policy, "op-1");
        item.publish("op-1");

        CatalogItemDocument document = CatalogItemPersistenceMapper.toDocument(item);
        CatalogItem restored = CatalogItemPersistenceMapper.toDomain(document);

        assertEquals(item.getId(), restored.getId());
        assertEquals("LAPTOP", restored.getCode());
        assertEquals(CatalogItemStatus.PUBLISHED, restored.getStatus());
        assertEquals(policy, restored.getApprovalPolicyId());
        assertEquals(item.getFields(), restored.getFields());
        assertEquals(72, restored.getFulfilmentTargetHours());
        assertEquals(item.getCreatedAt(), restored.getCreatedAt());
        assertEquals(item.getUpdatedAt(), restored.getUpdatedAt());
        assertEquals(2, restored.getAuditTrail().size());
        assertNull(restored.getAuditTrail().get(0).fromStatus());
        assertEquals(CatalogItemStatus.DRAFT, restored.getAuditTrail().get(1).fromStatus());
        assertEquals("op-1", document.getAuditTrail().get(0).executor());
        assertEquals(org, document.getOrganisationId());
        assertEquals("d", document.getDescription());
        assertEquals("HARDWARE", document.getCategory());
        assertEquals(2, document.getFields().size());
    }

    @Test
    @DisplayName("a request that went through its life survives storage: snapshot, answers, comments, times and the audit trail")
    void requestRoundTrip() {
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), org, "SEAT", "Software seat", null, null, List.of(new Field("model", "Which?", true)),
                24, UUID.randomUUID(), "op-1");
        item.publish("op-1");
        UUID requester = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        ServiceRequest request = ServiceRequest.createNew(UUID.randomUUID(), org, requester, item, Map.of("model", "X1"), approval, "req");
        request.assign(assignee, "op-1");
        request.approve("approver");
        request.addComment(new Comment(UUID.randomUUID(), "op-1", "private", true, null), "op-1");
        request.startFulfilment("op-1");
        request.fulfil("Delivered", "op-1");

        ServiceRequestDocument document = ServiceRequestPersistenceMapper.toDocument(request);
        ServiceRequest restored = ServiceRequestPersistenceMapper.toDomain(document);

        assertEquals(request.getId(), restored.getId());
        assertEquals(ServiceRequestStatus.FULFILLED, restored.getStatus());
        assertEquals(requester, restored.getRequesterId());
        assertEquals(item.getId(), restored.getCatalogItemId());
        assertEquals("SEAT", restored.getCatalogItemCode());
        assertEquals("Software seat", restored.getCatalogItemName());
        assertEquals(Map.of("model", "X1"), restored.getAnswers());
        assertEquals(assignee, restored.getAssigneeId());
        assertEquals(approval, restored.getApprovalRequestId());
        assertEquals(request.getFulfilmentDueAt(), restored.getFulfilmentDueAt());
        assertEquals(request.getStartedAt(), restored.getStartedAt());
        assertEquals(request.getFulfilledAt(), restored.getFulfilledAt());
        assertEquals("Delivered", restored.getFulfilmentNotes());
        assertEquals(1, restored.getComments().size());
        assertEquals(true, restored.getComments().get(0).internal());
        assertEquals(request.getAuditTrail().size(), restored.getAuditTrail().size());
        assertNull(restored.getAuditTrail().get(0).fromStatus());
        assertEquals(ServiceRequestStatus.PENDING_APPROVAL, restored.getAuditTrail().get(1).fromStatus());
        assertEquals("op-1", document.getAuditTrail().get(1).executor());
        assertEquals(org, document.getOrganisationId());
        assertEquals(request.getCreatedAt(), document.getCreatedAt());
        assertEquals(request.getUpdatedAt(), document.getUpdatedAt());
    }

    @Test
    @DisplayName("the persistence mappers are non-instantiable utility classes")
    void utilityClasses() throws Exception {
        for (Class<?> type : List.of(CatalogItemPersistenceMapper.class, ServiceRequestPersistenceMapper.class)) {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        }
    }
}
