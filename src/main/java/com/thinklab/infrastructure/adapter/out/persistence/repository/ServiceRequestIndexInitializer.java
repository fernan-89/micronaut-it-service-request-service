package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates the indexes of the two collections at startup, each matching a query the adapters really run. On {@code catalog_items}: the
 * UNIQUE {@code (organisationId, code)} (the atomic backstop of "one code per organisation", ADR-033) and the listing by
 * {@code (organisationId, status)}. On {@code service_requests}: the work queue ({@code organisationId, status}), "my requests"
 * ({@code organisationId, requesterId}), "assigned to me" ({@code organisationId, assigneeId}) and "requests of this item"
 * ({@code organisationId, catalogItemId}). The adapters use the driver directly, so the kit's generic {@code MongoIndexInitializer} does
 * not see them.
 *
 * <p>Fail-open: {@code createIndex} is idempotent; a failure is logged and the application still starts. Turn it off with
 * {@code thinklab.mongo.create-indexes=false}.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class ServiceRequestIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String CODE_INDEX = "organisationId_1_code_1";
    static final String CATALOG_STATUS_INDEX = "organisationId_1_status_1";
    static final String QUEUE_INDEX = "organisationId_1_status_1";
    static final String REQUESTER_INDEX = "organisationId_1_requesterId_1";
    static final String ASSIGNEE_INDEX = "organisationId_1_assigneeId_1";
    static final String CATALOG_ITEM_INDEX = "organisationId_1_catalogItemId_1";

    private static final Logger log = LoggerFactory.getLogger(ServiceRequestIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public ServiceRequestIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    ServiceRequestIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : ServiceRequestMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        String catalog = CatalogItemMongoRepositoryAdapter.COLLECTION_NAME;
        String requests = ServiceRequestMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(catalog, CODE_INDEX, new Document("organisationId", 1).append("code", 1), true);
        ensureIndex(catalog, CATALOG_STATUS_INDEX, new Document("organisationId", 1).append("status", 1), false);
        ensureIndex(requests, QUEUE_INDEX, new Document("organisationId", 1).append("status", 1), false);
        ensureIndex(requests, REQUESTER_INDEX, new Document("organisationId", 1).append("requesterId", 1), false);
        ensureIndex(requests, ASSIGNEE_INDEX, new Document("organisationId", 1).append("assigneeId", 1), false);
        ensureIndex(requests, CATALOG_ITEM_INDEX, new Document("organisationId", 1).append("catalogItemId", 1), false);
    }

    private void ensureIndex(String collection, String indexName, Document keys, boolean unique) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection)
                    .createIndex(keys, new IndexOptions().name(indexName).unique(unique))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", indexName, database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", indexName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", indexName, database, collection, e.getMessage());
        }
    }
}
