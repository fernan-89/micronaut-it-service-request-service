package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.repository.ServiceRequestRepository.Filter;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ServiceRequestDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ServiceRequestDocument.ServiceRequestPersistenceMapper;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ServiceRequestMongoRepositoryAdapterTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<ServiceRequestDocument> mongoCollection;

    private ServiceRequestMongoRepositoryAdapter adapter;
    private UUID organisationId;
    private ServiceRequest request;

    @BeforeEach
    void setUp() {
        lenient().when(mongoClient.getDatabase("thinklab_it_service_request_db")).thenReturn(mongoDatabase);
        lenient().when(mongoDatabase.getCollection("service_requests", ServiceRequestDocument.class)).thenReturn(mongoCollection);
        lenient().when(mongoCollection.withCodecRegistry(any())).thenReturn(mongoCollection);
        adapter = new ServiceRequestMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/thinklab_it_service_request_db");
        organisationId = UUID.randomUUID();
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), organisationId, "LAPTOP", "New laptop", null, null, List.of(new Field("model", "Which?", true)), 72, null, "op-1");
        item.publish("op-1");
        request = ServiceRequest.createNew(UUID.randomUUID(), organisationId, UUID.randomUUID(), item, Map.of("model", "X1"), null, "req");
    }

    private void finds(ServiceRequest... found) {
        FindPublisher<ServiceRequestDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        lenient().when(publisher.first()).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<ServiceRequestDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(ServiceRequestPersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("the database falls back to the service default when the URI has none")
    void databaseFallback() {
        ServiceRequestMongoRepositoryAdapter fallback = new ServiceRequestMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017");
        when(mongoCollection.insertOne(any(ServiceRequestDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(fallback.create(request)).expectNext(request).verifyComplete();
    }

    @Test
    @DisplayName("findById is scoped to the organisation and maps the document back")
    void findById() {
        finds(request);

        StepVerifier.create(adapter.findById(request.getId(), organisationId)).assertNext(found -> assertEquals(request.getId(), found.getId())).verifyComplete();
    }

    @Test
    @DisplayName("findAll applies every optional filter and the open-only exclusion")
    void findAll() {
        finds(request);
        UUID anyId = UUID.randomUUID();

        StepVerifier.create(adapter.findAll(organisationId, new Filter(ServiceRequestStatus.SUBMITTED, anyId, anyId, anyId, true))).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(organisationId, new Filter(null, null, null, null, false))).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection, times(2)).find(filter.capture());
        String full = filter.getAllValues().get(0).toString();
        assertTrue(full.contains("assigneeId") && full.contains("catalogItemId") && full.contains("requesterId") && full.contains("CLOSED") && full.contains("REJECTED"));
        String bare = filter.getAllValues().get(1).toString();
        assertTrue(bare.contains("organisationId") && !bare.contains("assigneeId") && !bare.contains("CLOSED"));
    }

    @Test
    @DisplayName("save is a guarded write on the organisation and the status loaded; a lost race is a 409-style conflict")
    void save() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        var entry = request.startFulfilment("op-1");

        StepVerifier.create(adapter.save(request, ServiceRequestStatus.SUBMITTED, entry)).verifyComplete();
        StepVerifier.create(adapter.save(request, ServiceRequestStatus.SUBMITTED, entry)).expectError(InvalidServiceRequestStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection, times(2)).updateOne(guard.capture(), any(Bson.class));
        assertTrue(guard.getAllValues().get(0).toString().contains("organisationId") && guard.getAllValues().get(0).toString().contains("SUBMITTED"));
    }

    @Test
    @DisplayName("addComment pushes the comment and the audit entry; an unknown request is not found")
    void addComment() {
        var comment = new Comment(UUID.randomUUID(), "op-1", "Checked", false, null);
        var entry = request.addComment(comment, "op-1");
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));

        StepVerifier.create(adapter.addComment(request.getId(), organisationId, comment, entry)).verifyComplete();
        StepVerifier.create(adapter.addComment(request.getId(), organisationId, comment, entry)).expectError(ServiceRequestNotFoundException.class).verify();
    }
}
