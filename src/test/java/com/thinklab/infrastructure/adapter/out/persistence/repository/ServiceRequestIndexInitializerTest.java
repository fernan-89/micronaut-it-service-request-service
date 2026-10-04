package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ServiceRequestIndexInitializerTest {

    private final StartupEvent startup = mock(StartupEvent.class);

    private MongoCollection<Document> collectionIn(MongoDatabase database, String name) {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection(name)).thenReturn(collection);
        return collection;
    }

    private MongoDatabase databaseIn(MongoClient client, String name) {
        MongoDatabase database = mock(MongoDatabase.class);
        when(client.getDatabase(name)).thenReturn(database);
        return database;
    }

    @Test
    @DisplayName("startup creates the unique code index and the listing index on the catalog, and the four request indexes, in the database named by mongodb.uri")
    void createsIndexes() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = databaseIn(client, "tenant_srq");
        MongoCollection<Document> catalog = collectionIn(database, "catalog_items");
        MongoCollection<Document> requests = collectionIn(database, "service_requests");
        when(catalog.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        when(requests.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new ServiceRequestIndexInitializer(client, "mongodb://mongo:27017/tenant_srq").onApplicationEvent(startup);

        ArgumentCaptor<Document> catalogKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> catalogOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(catalog, times(2)).createIndex(catalogKeys.capture(), catalogOptions.capture());
        assertEquals(List.of(new Document("organisationId", 1).append("code", 1), new Document("organisationId", 1).append("status", 1)), catalogKeys.getAllValues());
        assertEquals(true, catalogOptions.getAllValues().get(0).isUnique());
        assertEquals(false, catalogOptions.getAllValues().get(1).isUnique());

        ArgumentCaptor<Document> requestKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> requestOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(requests, times(4)).createIndex(requestKeys.capture(), requestOptions.capture());
        assertEquals(List.of(
                new Document("organisationId", 1).append("status", 1),
                new Document("organisationId", 1).append("requesterId", 1),
                new Document("organisationId", 1).append("assigneeId", 1),
                new Document("organisationId", 1).append("catalogItemId", 1)), requestKeys.getAllValues());
        assertEquals(List.of(ServiceRequestIndexInitializer.QUEUE_INDEX, ServiceRequestIndexInitializer.REQUESTER_INDEX,
                ServiceRequestIndexInitializer.ASSIGNEE_INDEX, ServiceRequestIndexInitializer.CATALOG_ITEM_INDEX),
                requestOptions.getAllValues().stream().map(IndexOptions::getName).toList());
    }

    @Test
    @DisplayName("a URI without a database uses the service default")
    void defaultDatabase() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = databaseIn(client, ServiceRequestMongoRepositoryAdapter.DEFAULT_DATABASE);
        MongoCollection<Document> catalog = collectionIn(database, "catalog_items");
        MongoCollection<Document> requests = collectionIn(database, "service_requests");
        when(catalog.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        when(requests.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new ServiceRequestIndexInitializer(client, "mongodb://mongo:27017").onApplicationEvent(startup);

        verify(catalog, times(2)).createIndex(any(), any(IndexOptions.class));
        verify(requests, times(4)).createIndex(any(), any(IndexOptions.class));
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated")
    void failOpen() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = databaseIn(client, "srq_db");
        MongoCollection<Document> catalog = collectionIn(database, "catalog_items");
        MongoCollection<Document> requests = collectionIn(database, "service_requests");
        when(catalog.createIndex(any(), any(IndexOptions.class)))
                .thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("rejected")));
        when(requests.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        assertDoesNotThrow(() -> new ServiceRequestIndexInitializer(client, "mongodb://mongo:27017/srq_db", Duration.ofSeconds(1)).onApplicationEvent(startup));
        verify(catalog, times(2)).createIndex(any(), any(IndexOptions.class));
        verify(requests, times(4)).createIndex(any(), any(IndexOptions.class));
    }

    @Test
    @DisplayName("collaborators, mongodb.uri and the startup event are null-checked")
    void guards() {
        MongoClient client = mock(MongoClient.class);
        assertThrows(NullPointerException.class, () -> new ServiceRequestIndexInitializer(null, "mongodb://mongo:27017/a"));
        assertThrows(NullPointerException.class, () -> new ServiceRequestIndexInitializer(client, null));
        assertThrows(NullPointerException.class, () -> new ServiceRequestIndexInitializer(client, "mongodb://mongo:27017/a").onApplicationEvent(null));
    }
}
