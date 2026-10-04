package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidCatalogItemStatusException;
import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRequestTest {

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    private CatalogItem item(UUID policy, boolean publish) {
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), org, "LAPTOP", "New laptop", null, "HARDWARE",
                List.of(new Field("model", "Which model?", true), new Field("notes", "Anything else?", false)), 72, policy, "op-1");
        if (publish) {
            item.publish("op-1");
        }
        return item;
    }

    private ServiceRequest request() {
        return ServiceRequest.createNew(UUID.randomUUID(), org, requester, item(null, true), Map.of("model", "X1"), null, "req-1");
    }

    private ServiceRequest pending() {
        return ServiceRequest.createNew(UUID.randomUUID(), org, requester, item(UUID.randomUUID(), true), Map.of("model", "X1"), UUID.randomUUID(), "req-1");
    }

    @Test
    @DisplayName("without an approval policy the request is SUBMITTED and snapshots the item, with the due date from its target")
    void submitted() {
        CatalogItem item = item(null, true);
        ServiceRequest request = ServiceRequest.createNew(UUID.randomUUID(), org, requester, item, Map.of("model", "X1"), null, "req-1");

        assertEquals(ServiceRequestStatus.SUBMITTED, request.getStatus());
        assertEquals("LAPTOP", request.getCatalogItemCode());
        assertEquals("New laptop", request.getCatalogItemName());
        assertEquals(item.getId(), request.getCatalogItemId());
        assertEquals(Map.of("model", "X1"), request.getAnswers());
        assertEquals(request.getCreatedAt().plus(Duration.ofHours(72)), request.getFulfilmentDueAt());
        assertNull(request.getApprovalRequestId());
        assertNull(request.getAuditTrail().get(0).fromStatus());
        assertTrue(request.getAuditTrail().get(0).detail().startsWith("Requested LAPTOP."));
    }

    @Test
    @DisplayName("with an approval policy the request starts PENDING_APPROVAL and carries the approval request id")
    void pendingApproval() {
        UUID approval = UUID.randomUUID();
        ServiceRequest request = ServiceRequest.createNew(UUID.randomUUID(), org, requester, item(UUID.randomUUID(), true), Map.of("model", "X1"), approval, "req-1");

        assertEquals(ServiceRequestStatus.PENDING_APPROVAL, request.getStatus());
        assertEquals(approval, request.getApprovalRequestId());
        assertTrue(request.getAuditTrail().get(0).detail().contains("waiting for approval"));
    }

    @Test
    @DisplayName("creation refuses what cannot be requested: missing parts, an unpublished item, another tenant's item, a wrong approval pairing, bad answers")
    void createGuards() {
        UUID id = UUID.randomUUID();
        CatalogItem published = item(null, true);
        Map<String, String> ok = Map.of("model", "X1");
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(null, org, requester, published, ok, null, "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, null, requester, published, ok, null, "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, org, null, published, ok, null, "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, org, requester, null, ok, null, "r"));
        assertThrows(InvalidCatalogItemStatusException.class, () -> ServiceRequest.createNew(id, org, requester, item(null, false), ok, null, "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, UUID.randomUUID(), requester, published, ok, null, "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, org, requester, published, ok, UUID.randomUUID(), "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, org, requester, item(UUID.randomUUID(), true), ok, null, "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, org, requester, published, null, null, "r"));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.createNew(id, org, requester, published, ok, null, " "));
    }

    @Test
    @DisplayName("answers are checked against the item's questions: unknown key, missing mandatory, blank mandatory, too long; optional ones may be absent")
    void answers() {
        CatalogItem item = item(null, true);
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.checkAnswers(item, Map.of("model", "X1", "colour", "red")));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.checkAnswers(item, Map.of("notes", "n")));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.checkAnswers(item, Map.of("model", " ")));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.checkAnswers(item, null));
        Map<String, String> tooLong = new HashMap<>();
        tooLong.put("model", "x".repeat(ServiceRequest.MAX_ANSWER_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.checkAnswers(item, tooLong));
        ServiceRequest.checkAnswers(item, Map.of("model", "x".repeat(ServiceRequest.MAX_ANSWER_LENGTH)));
        ServiceRequest.checkAnswers(item, Map.of("model", "X1", "notes", "n"));
    }

    @Test
    @DisplayName("the happy path SUBMITTED -> IN_FULFILMENT -> FULFILLED -> CLOSED is audited at every step")
    void happyPath() {
        ServiceRequest request = request();

        request.startFulfilment("op-1");
        assertNotNull(request.getStartedAt());
        var fulfilled = request.fulfil("Laptop handed over", "op-1");
        assertEquals(ServiceRequestStatus.IN_FULFILMENT, fulfilled.fromStatus());
        assertNotNull(request.getFulfilledAt());
        assertEquals("Laptop handed over", request.getFulfilmentNotes());
        request.close("op-1");

        assertEquals(ServiceRequestStatus.CLOSED, request.getStatus());
        assertEquals(4, request.getAuditTrail().size());
    }

    @Test
    @DisplayName("approval moves PENDING_APPROVAL to APPROVED (then fulfilment can start) or to REJECTED (terminal); nothing else can be approved")
    void approval() {
        ServiceRequest approved = pending();
        assertThrows(InvalidServiceRequestStatusException.class, () -> approved.startFulfilment("op-1"));
        approved.approve("approver-1");
        assertEquals(ServiceRequestStatus.APPROVED, approved.getStatus());
        approved.startFulfilment("op-1");
        assertEquals(ServiceRequestStatus.IN_FULFILMENT, approved.getStatus());
        assertThrows(InvalidServiceRequestStatusException.class, () -> approved.approve("approver-1"));

        ServiceRequest rejected = pending();
        rejected.reject("approver-1");
        assertEquals(ServiceRequestStatus.REJECTED, rejected.getStatus());
        assertThrows(InvalidServiceRequestStatusException.class, () -> rejected.cancel("op-1"));
        assertThrows(InvalidServiceRequestStatusException.class, () -> rejected.assign(UUID.randomUUID(), "op-1"));
        assertThrows(InvalidServiceRequestStatusException.class, () -> request().reject("approver-1"));
    }

    @Test
    @DisplayName("fulfil needs notes and the right status; close needs FULFILLED")
    void fulfilGuards() {
        ServiceRequest request = request();
        assertThrows(InvalidServiceRequestStatusException.class, () -> request.fulfil("done", "op-1"));
        assertThrows(InvalidServiceRequestStatusException.class, () -> request.close("op-1"));
        request.startFulfilment("op-1");
        assertThrows(IllegalArgumentException.class, () -> request.fulfil(null, "op-1"));
        assertThrows(IllegalArgumentException.class, () -> request.fulfil(" ", "op-1"));
        assertThrows(InvalidServiceRequestStatusException.class, () -> request.startFulfilment("op-1"));
    }

    @Test
    @DisplayName("cancel is legal until the request is fulfilled; terminal states refuse everything")
    void cancel() {
        for (ServiceRequest request : List.of(request(), pending())) {
            request.cancel("op-1");
            assertEquals(ServiceRequestStatus.CANCELLED, request.getStatus());
            assertThrows(InvalidServiceRequestStatusException.class, () -> request.cancel("op-1"));
            assertThrows(InvalidServiceRequestStatusException.class, () -> request.assign(UUID.randomUUID(), "op-1"));
            assertThrows(InvalidServiceRequestStatusException.class,
                    () -> request.addComment(new Comment(UUID.randomUUID(), "op-1", "hi", false, null), "op-1"));
        }
        ServiceRequest fulfilled = request();
        fulfilled.startFulfilment("op-1");
        fulfilled.fulfil("done", "op-1");
        assertThrows(InvalidServiceRequestStatusException.class, () -> fulfilled.cancel("op-1"));
        fulfilled.close("op-1");
        assertThrows(InvalidServiceRequestStatusException.class, () -> fulfilled.assign(UUID.randomUUID(), "op-1"));
    }

    @Test
    @DisplayName("assign records who works the request without changing its status; the assignee is mandatory")
    void assign() {
        ServiceRequest request = request();
        UUID assignee = UUID.randomUUID();

        var entry = request.assign(assignee, "op-1");

        assertEquals(assignee, request.getAssigneeId());
        assertEquals(ServiceRequestStatus.SUBMITTED, entry.toStatus());
        assertEquals("ASSIGNED", entry.action());
        assertThrows(NullPointerException.class, () -> request.assign(null, "op-1"));
    }

    @Test
    @DisplayName("comments are kept with their visibility and audited; a comment object and its parts are mandatory")
    void comments() {
        ServiceRequest request = request();

        var internal = request.addComment(new Comment(UUID.randomUUID(), "op-1", "private", true, null), "op-1");
        var open = request.addComment(new Comment(UUID.randomUUID(), "op-1", "public", false, Instant.now()), "op-1");

        assertEquals(2, request.getComments().size());
        assertEquals("Internal comment added.", internal.detail());
        assertEquals("Comment added.", open.detail());
        assertNotNull(request.getComments().get(0).createdAt());
        assertThrows(NullPointerException.class, () -> request.addComment(null, "op-1"));
        assertThrows(NullPointerException.class, () -> new Comment(null, "a", "t", false, null));
        assertThrows(IllegalArgumentException.class, () -> new Comment(UUID.randomUUID(), " ", "t", false, null));
        assertThrows(IllegalArgumentException.class, () -> new Comment(UUID.randomUUID(), null, "t", false, null));
        assertThrows(IllegalArgumentException.class, () -> new Comment(UUID.randomUUID(), "a", " ", false, null));
        assertThrows(IllegalArgumentException.class, () -> new Comment(UUID.randomUUID(), "a", null, false, null));
    }

    @Test
    @DisplayName("every mutation needs an executor")
    void executorRequired() {
        assertThrows(IllegalArgumentException.class, () -> request().startFulfilment(" "));
        assertThrows(IllegalArgumentException.class, () -> request().assign(UUID.randomUUID(), null));
    }

    @Test
    @DisplayName("reconstitute needs the identity and defaults the optional state")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.reconstitute(null, org, requester, item, "C", "n", null, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.reconstitute(id, null, requester, item, "C", "n", null, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.reconstitute(id, org, null, item, "C", "n", null, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.reconstitute(id, org, requester, null, "C", "n", null, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ServiceRequest.reconstitute(id, org, requester, item, null, "n", null, null, null, null, null, null, null, null, null, null, null, null));

        ServiceRequest bare = ServiceRequest.reconstitute(id, org, requester, item, "C", "n", null, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(ServiceRequestStatus.SUBMITTED, bare.getStatus());
        assertTrue(bare.getAnswers().isEmpty());
        assertTrue(bare.getComments().isEmpty());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());

        ServiceRequest source = request();
        source.assign(UUID.randomUUID(), "op-1");
        ServiceRequest full = ServiceRequest.reconstitute(source.getId(), org, requester, source.getCatalogItemId(), "LAPTOP", "New laptop", source.getAnswers(),
                ServiceRequestStatus.IN_FULFILMENT, source.getAssigneeId(), null, source.getFulfilmentDueAt(), Instant.now(), null, null, source.getComments(),
                source.getCreatedAt(), source.getUpdatedAt(), source.getAuditTrail());
        assertEquals(ServiceRequestStatus.IN_FULFILMENT, full.getStatus());
        assertEquals(source.getUpdatedAt(), full.getUpdatedAt());
        assertEquals(2, full.getAuditTrail().size());
    }
}
