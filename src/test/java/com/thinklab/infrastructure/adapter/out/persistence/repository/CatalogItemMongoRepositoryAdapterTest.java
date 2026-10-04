package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateCatalogItemException;
import com.thinklab.domain.exception.InvalidCatalogItemStatusException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CatalogItemDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CatalogItemDocument.CatalogItemPersistenceMapper;
import org.bson.BsonDocument;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class CatalogItemMongoRepositoryAdapterTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<CatalogItemDocument> mongoCollection;

    private CatalogItemMongoRepositoryAdapter adapter;
    private UUID organisationId;
    private CatalogItem item;

    @BeforeEach
    void setUp() {
        lenient().when(mongoClient.getDatabase("thinklab_it_service_request_db")).thenReturn(mongoDatabase);
        lenient().when(mongoDatabase.getCollection("catalog_items", CatalogItemDocument.class)).thenReturn(mongoCollection);
        lenient().when(mongoCollection.withCodecRegistry(any())).thenReturn(mongoCollection);
        adapter = new CatalogItemMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/thinklab_it_service_request_db");
        organisationId = UUID.randomUUID();
        item = CatalogItem.createNew(UUID.randomUUID(), organisationId, "LAPTOP", "New laptop", null, "HARDWARE", List.of(new Field("model", "Which?", true)), 72, null, "op-1");
    }

    private void finds(CatalogItem... found) {
        FindPublisher<CatalogItemDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        lenient().when(publisher.first()).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<CatalogItemDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(CatalogItemPersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("the database falls back to the service default when the URI has none")
    void databaseFallback() {
        CatalogItemMongoRepositoryAdapter fallback = new CatalogItemMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017");
        when(mongoCollection.insertOne(any(CatalogItemDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(fallback.create(item)).expectNext(item).verifyComplete();
    }

    @Test
    @DisplayName("create inserts the whole aggregate; a duplicate-key error on (organisation, code) becomes DuplicateCatalogItemException; other errors pass through")
    void create() {
        when(mongoCollection.insertOne(any(CatalogItemDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))))
                .thenReturn(Mono.error(new MongoWriteException(new WriteError(11000, "E11000 duplicate key", new BsonDocument()), new ServerAddress())))
                .thenReturn(Mono.error(new MongoWriteException(new WriteError(121, "validation", new BsonDocument()), new ServerAddress())))
                .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(adapter.create(item)).expectNext(item).verifyComplete();
        StepVerifier.create(adapter.create(item)).expectError(DuplicateCatalogItemException.class).verify();
        StepVerifier.create(adapter.create(item)).expectError(MongoWriteException.class).verify();
        StepVerifier.create(adapter.create(item)).expectError(IllegalStateException.class).verify();
    }

    @Test
    @DisplayName("isDuplicateKey recognises only a duplicate-key write error")
    void isDuplicateKey() {
        assertTrue(CatalogItemMongoRepositoryAdapter.isDuplicateKey(new MongoWriteException(new WriteError(11000, "dup", new BsonDocument()), new ServerAddress())));
        assertFalse(CatalogItemMongoRepositoryAdapter.isDuplicateKey(new MongoWriteException(new WriteError(121, "other", new BsonDocument()), new ServerAddress())));
        assertFalse(CatalogItemMongoRepositoryAdapter.isDuplicateKey(new RuntimeException()));
    }

    @Test
    @DisplayName("findById and findByCode are scoped to the organisation and map the document back")
    void finders() {
        finds(item);

        StepVerifier.create(adapter.findById(item.getId(), organisationId)).assertNext(found -> assertEquals(item.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findByCode("LAPTOP", organisationId)).assertNext(found -> assertEquals("LAPTOP", found.getCode())).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(filter.getAllValues().get(1).toString().contains("code"));
    }

    @Test
    @DisplayName("findAll filters by status only when one is given")
    void findAll() {
        finds(item);

        StepVerifier.create(adapter.findAll(organisationId, CatalogItemStatus.PUBLISHED)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(organisationId, null)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("PUBLISHED"));
        assertFalse(filter.getAllValues().get(1).toString().contains("status"));
    }

    @Test
    @DisplayName("save is a guarded write needing the organisation and the status the item was loaded in; a lost race is a 409-style conflict")
    void save() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        var entry = item.publish("op-1");

        StepVerifier.create(adapter.save(item, CatalogItemStatus.DRAFT, entry)).verifyComplete();
        StepVerifier.create(adapter.save(item, CatalogItemStatus.DRAFT, entry)).expectError(InvalidCatalogItemStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection, times(2)).updateOne(guard.capture(), any(Bson.class));
        assertTrue(guard.getAllValues().get(0).toString().contains("organisationId") && guard.getAllValues().get(0).toString().contains("DRAFT"));
    }
}
