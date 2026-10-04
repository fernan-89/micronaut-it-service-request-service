package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.FieldRequest;
import com.thinklab.application.dto.request.InitiateCatalogItemRequest;
import com.thinklab.application.dto.response.CatalogItemResponse;
import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MappersTest {

    private final UUID org = UUID.randomUUID();

    private CatalogItem publishedItem() {
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), org, "LAPTOP", "New laptop", "d", "HARDWARE",
                List.of(new Field("model", "Which model?", true)), 72, null, "op-1");
        item.publish("op-1");
        return item;
    }

    @Test
    @DisplayName("a catalog request becomes a DRAFT item, and the item maps back with its questions and audit entries")
    void catalogMapping() {
        var request = new InitiateCatalogItemRequest("vpn", "VPN access", "d", "ACCESS", List.of(new FieldRequest("reason", "Why?", true)), 24, null);

        CatalogItem item = CatalogItemMapper.toDomain(request, UUID.randomUUID(), org, "op-1");
        CatalogItemResponse response = CatalogItemMapper.toResponse(item);

        assertEquals("VPN", response.code());
        assertEquals("DRAFT", response.status());
        assertEquals("reason", response.fields().get(0).key());
        assertNull(CatalogItemMapper.toResponse(item.getAuditTrail().get(0)).fromStatus());
        item.publish("op-1");
        assertEquals("DRAFT", CatalogItemMapper.toResponse(item.getAuditTrail().get(1)).fromStatus());
        assertEquals("PUBLISHED", CatalogItemMapper.toResponse(item.getAuditTrail().get(1)).toStatus());
    }

    @Test
    @DisplayName("internal comments appear only when asked for, and a cancelled or rejected request reports no SLA")
    void requestMapping() {
        ServiceRequest request = ServiceRequest.createNew(UUID.randomUUID(), org, UUID.randomUUID(), publishedItem(), Map.of("model", "X1"), null, "req");
        request.addComment(new Comment(UUID.randomUUID(), "op", "private", true, null), "op");
        request.addComment(new Comment(UUID.randomUUID(), "op", "public", false, null), "op");

        ServiceRequestResponse staff = ServiceRequestMapper.toResponse(request, true, Instant.now());
        ServiceRequestResponse requester = ServiceRequestMapper.toResponse(request, false, Instant.now());

        assertEquals(2, staff.comments().size());
        assertEquals(1, requester.comments().size());
        assertEquals("PENDING", staff.fulfilment().state());
        assertNull(ServiceRequestMapper.toResponse(request, true, Instant.now()).fulfilmentNotes());
        assertNull(ServiceRequestMapper.toResponse(request, true, Instant.now()).fulfilledAt());

        request.cancel("op");
        assertNull(ServiceRequestMapper.toResponse(request, true, Instant.now()).fulfilment());
        ServiceRequest rejected = ServiceRequest.createNew(UUID.randomUUID(), org, UUID.randomUUID(), approvalItem(), Map.of("model", "X1"), UUID.randomUUID(), "req");
        rejected.reject("approver");
        assertNull(ServiceRequestMapper.toResponse(rejected, true, Instant.now()).fulfilment());
        assertNull(ServiceRequestMapper.toResponse(request.getAuditTrail().get(0)).fromStatus());
        assertEquals("SUBMITTED", ServiceRequestMapper.toResponse(request.getAuditTrail().get(3)).fromStatus());
    }

    private CatalogItem approvalItem() {
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), org, "SEAT", "Software seat", null, null,
                List.of(new Field("model", "Which?", true)), 24, UUID.randomUUID(), "op-1");
        item.publish("op-1");
        return item;
    }

    @Test
    @DisplayName("the SLA is PENDING before the due date, BREACHED after it, and MET or BREACHED by when the work was done")
    void sla() {
        Instant due = Instant.parse("2026-10-04T12:00:00Z");

        assertEquals("PENDING", ServiceRequestMapper.sla(due, null, due.minus(Duration.ofHours(1))).state());
        assertEquals("BREACHED", ServiceRequestMapper.sla(due, null, due.plus(Duration.ofHours(1))).state());
        assertEquals("MET", ServiceRequestMapper.sla(due, due.minus(Duration.ofHours(1)), due.plus(Duration.ofDays(1))).state());
        assertEquals("BREACHED", ServiceRequestMapper.sla(due, due.plus(Duration.ofHours(1)), due.plus(Duration.ofDays(1))).state());
        assertEquals(due, ServiceRequestMapper.sla(due, null, due).dueAt());
    }

    @Test
    @DisplayName("the mappers are non-instantiable utility classes")
    void utilityClasses() throws Exception {
        for (Class<?> type : List.of(CatalogItemMapper.class, ServiceRequestMapper.class)) {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        }
    }
}
