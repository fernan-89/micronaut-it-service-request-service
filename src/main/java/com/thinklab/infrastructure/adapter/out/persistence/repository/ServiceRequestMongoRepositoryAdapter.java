package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.RequestAuditEntry;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.repository.ServiceRequestRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ServiceRequestDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ServiceRequestDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ServiceRequestDocument.CommentDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ServiceRequestDocument.ServiceRequestPersistenceMapper;
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
 * MongoDB Reactive Repository Adapter for the ServiceRequest aggregate, raw reactive-streams driver. Every change is a single atomic
 * {@code $set}/{@code $push} that also appends the forensic audit entry, and every filter carries the organisation: another tenant's
 * request is simply not found.
 */
@Singleton
public class ServiceRequestMongoRepositoryAdapter implements ServiceRequestRepository {

    private static final Logger log = LoggerFactory.getLogger(ServiceRequestMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = CatalogItemMongoRepositoryAdapter.DEFAULT_DATABASE;
    static final String COLLECTION_NAME = "service_requests";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_AUDIT_TRAIL = "auditTrail";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public ServiceRequestMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<ServiceRequestDocument> getCollection() {
        return mongoClient.getDatabase(database)
                .getCollection(COLLECTION_NAME, ServiceRequestDocument.class)
                .withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<ServiceRequest> create(ServiceRequest request) {
        log.debug("[PERSISTENCE] Monolithic create for ServiceRequest Aggregate: {}", request.getId());

        return Mono.from(getCollection().insertOne(ServiceRequestPersistenceMapper.toDocument(request))).map(result -> request);
    }

    @Override
    public Mono<ServiceRequest> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(ServiceRequestPersistenceMapper::toDomain);
    }

    @Override
    public Flux<ServiceRequest> findAll(UUID organisationId, Filter filter) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (filter.status() != null) {
            filters.add(Filters.eq(FIELD_STATUS, filter.status().name()));
        }
        if (filter.assigneeId() != null) {
            filters.add(Filters.eq("assigneeId", filter.assigneeId()));
        }
        if (filter.catalogItemId() != null) {
            filters.add(Filters.eq("catalogItemId", filter.catalogItemId()));
        }
        if (filter.requesterId() != null) {
            filters.add(Filters.eq("requesterId", filter.requesterId()));
        }
        if (filter.openOnly()) {
            filters.add(Filters.nin(FIELD_STATUS, ServiceRequestStatus.FULFILLED.name(), ServiceRequestStatus.CLOSED.name(),
                    ServiceRequestStatus.CANCELLED.name(), ServiceRequestStatus.REJECTED.name()));
        }

        return Flux.from(getCollection().find(Filters.and(filters))).map(ServiceRequestPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(ServiceRequest request, ServiceRequestStatus expectedStatus, RequestAuditEntry auditEntry) {
        // Guarded write: it only applies while the request still has the status it had when it was loaded.
        Bson guard = Filters.and(Filters.eq(FIELD_ID, request.getId()), Filters.eq(FIELD_ORGANISATION, request.getOrganisationId()),
                Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set(FIELD_STATUS, request.getStatus().name()),
                Updates.set("assigneeId", request.getAssigneeId()),
                Updates.set("startedAt", request.getStartedAt()),
                Updates.set("fulfilledAt", request.getFulfilledAt()),
                Updates.set("fulfilmentNotes", request.getFulfilmentNotes()),
                Updates.set("returnReason", request.getReturnReason()),
                Updates.set("answers", new java.util.LinkedHashMap<>(request.getAnswers())),
                Updates.set("approvalRequestId", request.getApprovalRequestId()),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidServiceRequestStatusException("ServiceRequest was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty());
    }

    @Override
    public Mono<Void> addComment(UUID id, UUID organisationId, Comment comment, RequestAuditEntry auditEntry) {
        Bson update = Updates.combine(
                Updates.push("comments", CommentDocument.fromDomain(comment)),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId)), update))
                .flatMap(result -> result.getMatchedCount() == 0 ? Mono.error(new ServiceRequestNotFoundException(id)) : Mono.<Void>empty());
    }
}
