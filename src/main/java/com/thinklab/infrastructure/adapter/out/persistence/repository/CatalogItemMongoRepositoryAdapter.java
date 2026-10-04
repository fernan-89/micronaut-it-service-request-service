package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateCatalogItemException;
import com.thinklab.domain.exception.InvalidCatalogItemStatusException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogAuditEntry;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.repository.CatalogItemRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CatalogItemDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CatalogItemDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CatalogItemDocument.CatalogItemPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CatalogItemDocument.FieldDocument;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the CatalogItem aggregate, raw reactive-streams driver. Each change is one atomic
 * {@code $set}/{@code $push} that also appends the audit entry; every filter carries the organisation. The unique
 * {@code (organisationId, code)} index is the atomic backstop of the "code already taken" rule (ADR-033).
 */
@Singleton
public class CatalogItemMongoRepositoryAdapter implements CatalogItemRepository {

    private static final Logger log = LoggerFactory.getLogger(CatalogItemMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_service_request_db";
    static final String COLLECTION_NAME = "catalog_items";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public CatalogItemMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<CatalogItemDocument> getCollection() {
        return mongoClient.getDatabase(database)
                .getCollection(COLLECTION_NAME, CatalogItemDocument.class)
                .withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<CatalogItem> create(CatalogItem item) {
        log.debug("[PERSISTENCE] Monolithic create for CatalogItem Aggregate: {}", item.getId());

        return Mono.from(getCollection().insertOne(CatalogItemPersistenceMapper.toDocument(item)))
                .map(result -> item)
                .onErrorMap(CatalogItemMongoRepositoryAdapter::isDuplicateKey, e -> new DuplicateCatalogItemException(item.getCode()));
    }

    static boolean isDuplicateKey(Throwable error) {
        return error instanceof MongoWriteException write && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }

    @Override
    public Mono<CatalogItem> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(CatalogItemPersistenceMapper::toDomain);
    }

    @Override
    public Mono<CatalogItem> findByCode(String code, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq("code", code), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(CatalogItemPersistenceMapper::toDomain);
    }

    @Override
    public Flux<CatalogItem> findAll(UUID organisationId, CatalogItemStatus status) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (status != null) {
            filters.add(Filters.eq(FIELD_STATUS, status.name()));
        }
        return Flux.from(getCollection().find(Filters.and(filters))).map(CatalogItemPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(CatalogItem item, CatalogItemStatus expectedStatus, CatalogAuditEntry auditEntry) {
        // Guarded write: it only applies while the item still has the status it had when it was loaded.
        Bson guard = Filters.and(Filters.eq(FIELD_ID, item.getId()), Filters.eq(FIELD_ORGANISATION, item.getOrganisationId()),
                Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set("name", item.getName()),
                Updates.set("description", item.getDescription()),
                Updates.set("category", item.getCategory()),
                Updates.set("fields", item.getFields().stream().map(FieldDocument::fromDomain).toList()),
                Updates.set("fulfilmentTargetHours", item.getFulfilmentTargetHours()),
                Updates.set("approvalPolicyId", item.getApprovalPolicyId()),
                Updates.set(FIELD_STATUS, item.getStatus().name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidCatalogItemStatusException("Catalog item was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty());
    }
}
