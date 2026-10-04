package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateCatalogItemException;
import com.thinklab.domain.exception.InvalidCatalogItemStatusException;
import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.repository.CatalogItemRepository;
import com.thinklab.domain.repository.ServiceRequestRepository;
import com.thinklab.domain.repository.ServiceRequestRepository.Filter;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog and the requests through their repositories against a real MongoDB: the unique code index (the atomic backstop of "one
 * code per organisation"), the guarded saves (a second writer from the same state loses), tenant scoping, every request filter,
 * comments, and the indexes {@link com.thinklab.infrastructure.adapter.out.persistence.repository.ServiceRequestIndexInitializer}
 * creates at startup.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceRequestPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "service_request_it";
    private static final String EXECUTOR = "op-1";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject
    CatalogItemRepository catalog;

    @Inject
    ServiceRequestRepository requests;

    @Inject
    MongoClient mongoClient;

    private CatalogItem newItem(UUID organisation, String code, UUID policy) {
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), organisation, code, "New laptop", "d", "HARDWARE",
                List.of(new Field("model", "Which model?", true)), 72, policy, EXECUTOR);
        item.publish(EXECUTOR);
        return item;
    }

    private ServiceRequest newRequest(UUID organisation, UUID requester, CatalogItem item) {
        return ServiceRequest.createNew(UUID.randomUUID(), organisation, requester, item, Map.of("model", "X1"),
                item.getApprovalPolicyId() != null ? UUID.randomUUID() : null, EXECUTOR);
    }

    private static Set<UUID> ids(List<ServiceRequest> found) {
        return found.stream().map(ServiceRequest::getId).collect(Collectors.toSet());
    }

    private List<ServiceRequest> list(UUID organisation, Filter filter) {
        return requests.findAll(organisation, filter).collectList().block();
    }

    @Test
    @DisplayName("a created catalog item is read back whole, by id and by code, inside its tenant only")
    void catalogCreateAndFind() {
        UUID organisation = UUID.randomUUID();
        CatalogItem created = catalog.create(newItem(organisation, "LAPTOP", UUID.randomUUID())).block();

        CatalogItem byId = catalog.findById(created.getId(), organisation).block();
        CatalogItem byCode = catalog.findByCode("LAPTOP", organisation).block();

        assertEquals(CatalogItemStatus.PUBLISHED, byId.getStatus());
        assertEquals(created.getApprovalPolicyId(), byId.getApprovalPolicyId());
        assertEquals(1, byId.getFields().size());
        assertEquals(2, byId.getAuditTrail().size());
        assertEquals(created.getId(), byCode.getId());
        assertNull(catalog.findById(created.getId(), UUID.randomUUID()).block());
        assertNull(catalog.findByCode("LAPTOP", UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("the unique (organisation, code) index refuses a second item with the same code, but another tenant may reuse it")
    void duplicateCode() {
        UUID organisation = UUID.randomUUID();
        catalog.create(newItem(organisation, "VPN", null)).block();

        assertThrows(DuplicateCatalogItemException.class, () -> catalog.create(newItem(organisation, "VPN", null)).block());
        assertEquals("VPN", catalog.create(newItem(UUID.randomUUID(), "VPN", null)).block().getCode());
    }

    @Test
    @DisplayName("the catalog list honours the status filter, inside the tenant")
    void catalogFilter() {
        UUID organisation = UUID.randomUUID();
        CatalogItem published = catalog.create(newItem(organisation, "A", null)).block();
        CatalogItem draft = catalog.create(CatalogItem.createNew(UUID.randomUUID(), organisation, "B", "Draft", null, null, List.of(), 8, null, EXECUTOR)).block();
        catalog.create(newItem(UUID.randomUUID(), "C", null)).block();

        Set<UUID> all = catalog.findAll(organisation, null).map(CatalogItem::getId).collect(Collectors.toSet()).block();
        Set<UUID> onlyPublished = catalog.findAll(organisation, CatalogItemStatus.PUBLISHED).map(CatalogItem::getId).collect(Collectors.toSet()).block();

        assertEquals(Set.of(published.getId(), draft.getId()), all);
        assertEquals(Set.of(published.getId()), onlyPublished);
    }

    @Test
    @DisplayName("a catalog save is guarded: the second writer from the same state is refused, not merged")
    void catalogGuardedSave() {
        UUID organisation = UUID.randomUUID();
        CatalogItem created = catalog.create(CatalogItem.createNew(UUID.randomUUID(), organisation, "G", "Guarded", null, null, List.of(), 8, null, EXECUTOR)).block();
        CatalogItem seenByA = catalog.findById(created.getId(), organisation).block();
        CatalogItem seenByB = catalog.findById(created.getId(), organisation).block();
        var entryA = seenByA.publish("op-a");
        var entryB = seenByB.updateDetails("Renamed", null, null, List.of(), 8, null, "op-b");

        catalog.save(seenByA, CatalogItemStatus.DRAFT, entryA).block();

        assertThrows(InvalidCatalogItemStatusException.class, () -> catalog.save(seenByB, CatalogItemStatus.DRAFT, entryB).block());
        CatalogItem found = catalog.findById(created.getId(), organisation).block();
        assertEquals(CatalogItemStatus.PUBLISHED, found.getStatus());
        assertEquals("Guarded", found.getName());
        assertEquals(2, found.getAuditTrail().size());
    }

    @Test
    @DisplayName("a created request is read back whole: snapshot, answers, SLA due date and its INITIATED audit entry")
    void requestCreateAndFind() {
        UUID organisation = UUID.randomUUID();
        CatalogItem item = catalog.create(newItem(organisation, "LAPTOP", null)).block();
        ServiceRequest created = requests.create(newRequest(organisation, UUID.randomUUID(), item)).block();

        ServiceRequest found = requests.findById(created.getId(), organisation).block();

        assertEquals(ServiceRequestStatus.SUBMITTED, found.getStatus());
        assertEquals("LAPTOP", found.getCatalogItemCode());
        assertEquals(Map.of("model", "X1"), found.getAnswers());
        assertEquals(created.getFulfilmentDueAt().toEpochMilli(), found.getFulfilmentDueAt().toEpochMilli());
        assertEquals(1, found.getAuditTrail().size());
        assertNull(requests.findById(created.getId(), UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("save persists the state the request reached with its audit entry; a second writer from the same state is refused")
    void requestGuardedSave() {
        UUID organisation = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        CatalogItem item = catalog.create(newItem(organisation, "LAPTOP", null)).block();
        ServiceRequest created = requests.create(newRequest(organisation, UUID.randomUUID(), item)).block();
        ServiceRequest seenByA = requests.findById(created.getId(), organisation).block();
        ServiceRequest seenByB = requests.findById(created.getId(), organisation).block();
        seenByA.assign(assignee, "op-a");
        var entryA = seenByA.startFulfilment("op-a");
        var entryB = seenByB.cancel("op-b");

        requests.save(seenByA, ServiceRequestStatus.SUBMITTED, entryA).block();

        assertThrows(InvalidServiceRequestStatusException.class, () -> requests.save(seenByB, ServiceRequestStatus.SUBMITTED, entryB).block());
        ServiceRequest found = requests.findById(created.getId(), organisation).block();
        assertEquals(ServiceRequestStatus.IN_FULFILMENT, found.getStatus());
        assertEquals(assignee, found.getAssigneeId());
        assertEquals(seenByA.getStartedAt().toEpochMilli(), found.getStartedAt().toEpochMilli());
        assertEquals(2, found.getAuditTrail().size());
    }

    @Test
    @DisplayName("addComment pushes the comment and its audit entry; an unknown request is ServiceRequestNotFoundException")
    void comments() {
        UUID organisation = UUID.randomUUID();
        CatalogItem item = catalog.create(newItem(organisation, "LAPTOP", null)).block();
        ServiceRequest created = requests.create(newRequest(organisation, UUID.randomUUID(), item)).block();
        var comment = new Comment(UUID.randomUUID(), EXECUTOR, "Stock is on its way", true, null);
        var entry = created.addComment(comment, EXECUTOR);

        requests.addComment(created.getId(), organisation, comment, entry).block();

        ServiceRequest found = requests.findById(created.getId(), organisation).block();
        assertEquals(1, found.getComments().size());
        assertTrue(found.getComments().get(0).internal());
        assertEquals(2, found.getAuditTrail().size());
        assertThrows(ServiceRequestNotFoundException.class, () -> requests.addComment(UUID.randomUUID(), organisation, comment, entry).block());
        assertThrows(ServiceRequestNotFoundException.class, () -> requests.addComment(created.getId(), UUID.randomUUID(), comment, entry).block());
    }

    @Test
    @DisplayName("the request collection honours status, assignee, catalog item, requester and open-only, always inside the tenant")
    void requestFilters() {
        UUID organisation = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        CatalogItem laptop = catalog.create(newItem(organisation, "LAPTOP", null)).block();
        CatalogItem seat = catalog.create(newItem(organisation, "SEAT", UUID.randomUUID())).block();
        ServiceRequest open = requests.create(newRequest(organisation, requester, laptop)).block();
        ServiceRequest waiting = requests.create(newRequest(organisation, UUID.randomUUID(), seat)).block();
        ServiceRequest cancelled = requests.create(newRequest(organisation, UUID.randomUUID(), laptop)).block();
        requests.create(newRequest(UUID.randomUUID(), requester, newItem(UUID.randomUUID(), "LAPTOP", null))).block();
        var assignment = open.assign(assignee, EXECUTOR);
        requests.save(open, ServiceRequestStatus.SUBMITTED, assignment).block();
        var cancellation = cancelled.cancel(EXECUTOR);
        requests.save(cancelled, ServiceRequestStatus.SUBMITTED, cancellation).block();

        assertEquals(Set.of(open.getId(), waiting.getId(), cancelled.getId()), ids(list(organisation, new Filter(null, null, null, null, false))));
        assertEquals(Set.of(open.getId(), waiting.getId()), ids(list(organisation, new Filter(null, null, null, null, true))));
        assertEquals(Set.of(waiting.getId()), ids(list(organisation, new Filter(ServiceRequestStatus.PENDING_APPROVAL, null, null, null, false))));
        assertEquals(Set.of(open.getId()), ids(list(organisation, new Filter(null, assignee, null, null, false))));
        assertEquals(Set.of(waiting.getId()), ids(list(organisation, new Filter(null, null, seat.getId(), null, false))));
        assertEquals(Set.of(open.getId()), ids(list(organisation, new Filter(null, null, null, requester, false))));
        assertTrue(list(UUID.randomUUID(), new Filter(null, null, null, null, false)).isEmpty());
    }

    @Test
    @DisplayName("the indexes exist: the unique code on the catalog, and the four of the requests")
    void indexesExist() {
        UUID organisation = UUID.randomUUID();
        catalog.create(newItem(organisation, "IDX", null)).block();

        List<Document> catalogIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("catalog_items").listIndexes()).collectList().block();
        Document code = catalogIndexes.stream().filter(index -> index.get("key", Document.class).equals(new Document("organisationId", 1).append("code", 1))).findFirst().orElseThrow();
        assertEquals(true, code.getBoolean("unique"), () -> "catalog_items: " + catalogIndexes);

        ServiceRequest created = requests.create(newRequest(organisation, UUID.randomUUID(), catalog.findByCode("IDX", organisation).block())).block();
        assertEquals(ServiceRequestStatus.SUBMITTED, created.getStatus());
        List<Document> requestIndexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("service_requests").listIndexes()).collectList().block();
        Set<Document> keys = requestIndexes.stream().map(index -> index.get("key", Document.class)).collect(Collectors.toSet());
        assertTrue(keys.contains(new Document("organisationId", 1).append("status", 1)), () -> "service_requests: " + requestIndexes);
        assertTrue(keys.contains(new Document("organisationId", 1).append("requesterId", 1)));
        assertTrue(keys.contains(new Document("organisationId", 1).append("assigneeId", 1)));
        assertTrue(keys.contains(new Document("organisationId", 1).append("catalogItemId", 1)));
    }
}
