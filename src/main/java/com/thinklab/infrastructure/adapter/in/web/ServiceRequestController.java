package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.AssignServiceRequestRequest;
import com.thinklab.application.dto.request.CaptureApprovalDecisionRequest;
import com.thinklab.application.dto.request.FulfilServiceRequestRequest;
import com.thinklab.application.dto.request.InitiateCatalogItemRequest;
import com.thinklab.application.dto.request.InitiateCommentRequest;
import com.thinklab.application.dto.request.InitiateServiceRequestRequest;
import com.thinklab.application.dto.request.UpdateCatalogItemRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.CatalogItemResponse;
import com.thinklab.application.dto.response.ServiceRequestResponse;
import com.thinklab.application.usecase.AssignServiceRequestUseCase;
import com.thinklab.application.usecase.CancelServiceRequestUseCase;
import com.thinklab.application.usecase.CaptureApprovalDecisionUseCase;
import com.thinklab.application.usecase.ControlCatalogItemUseCase;
import com.thinklab.application.usecase.ControlServiceRequestUseCase;
import com.thinklab.application.usecase.FulfilServiceRequestUseCase;
import com.thinklab.application.usecase.InitiateCatalogItemUseCase;
import com.thinklab.application.usecase.InitiateCommentUseCase;
import com.thinklab.application.usecase.InitiateServiceRequestUseCase;
import com.thinklab.application.usecase.RetrieveCatalogAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveCatalogItemUseCase;
import com.thinklab.application.usecase.RetrieveCatalogItemsUseCase;
import com.thinklab.application.usecase.RetrieveServiceRequestAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveServiceRequestUseCase;
import com.thinklab.application.usecase.RetrieveServiceRequestsUseCase;
import com.thinklab.application.usecase.UpdateCatalogItemUseCase;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-service-request} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.ServiceRequest} is the Control Record, routed at the
 * root; the {@link com.thinklab.domain.model.CatalogItem} it is ordered from is a secondary aggregate under the {@code catalog/} prefix
 * (the precedent of workflow-approval's {@code policy/}). Every route follows
 * {@code /it-service-request/v1/{control-record-id}/{behavior-qualifier}}. There is no {@code DELETE}: {@code control/cancel},
 * {@code control/close} and {@code control/retire} are terminal, soft status transitions.
 *
 * <p><b>Tenant on every route:</b> {@code X-Tenant-Id} is mandatory everywhere and every lookup is scoped to it. {@code X-Role}
 * ({@code REQUESTER} for self-service) narrows a requester to the published catalog and to their own requests (ADR-031).
 */
@Controller("/it-service-request/v1")
public class ServiceRequestController {

    private static final Logger log = LoggerFactory.getLogger(ServiceRequestController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final InitiateCatalogItemUseCase initiateCatalogItemUseCase;
    private final RetrieveCatalogItemUseCase retrieveCatalogItemUseCase;
    private final RetrieveCatalogItemsUseCase retrieveCatalogItemsUseCase;
    private final UpdateCatalogItemUseCase updateCatalogItemUseCase;
    private final ControlCatalogItemUseCase controlCatalogItemUseCase;
    private final RetrieveCatalogAuditLogUseCase retrieveCatalogAuditLogUseCase;
    private final InitiateServiceRequestUseCase initiateServiceRequestUseCase;
    private final RetrieveServiceRequestUseCase retrieveServiceRequestUseCase;
    private final RetrieveServiceRequestsUseCase retrieveServiceRequestsUseCase;
    private final AssignServiceRequestUseCase assignServiceRequestUseCase;
    private final CaptureApprovalDecisionUseCase captureApprovalDecisionUseCase;
    private final ControlServiceRequestUseCase controlServiceRequestUseCase;
    private final FulfilServiceRequestUseCase fulfilServiceRequestUseCase;
    private final CancelServiceRequestUseCase cancelServiceRequestUseCase;
    private final InitiateCommentUseCase initiateCommentUseCase;
    private final RetrieveServiceRequestAuditLogUseCase retrieveServiceRequestAuditLogUseCase;

    public ServiceRequestController(
            InitiateCatalogItemUseCase initiateCatalogItemUseCase,
            RetrieveCatalogItemUseCase retrieveCatalogItemUseCase,
            RetrieveCatalogItemsUseCase retrieveCatalogItemsUseCase,
            UpdateCatalogItemUseCase updateCatalogItemUseCase,
            ControlCatalogItemUseCase controlCatalogItemUseCase,
            RetrieveCatalogAuditLogUseCase retrieveCatalogAuditLogUseCase,
            InitiateServiceRequestUseCase initiateServiceRequestUseCase,
            RetrieveServiceRequestUseCase retrieveServiceRequestUseCase,
            RetrieveServiceRequestsUseCase retrieveServiceRequestsUseCase,
            AssignServiceRequestUseCase assignServiceRequestUseCase,
            CaptureApprovalDecisionUseCase captureApprovalDecisionUseCase,
            ControlServiceRequestUseCase controlServiceRequestUseCase,
            FulfilServiceRequestUseCase fulfilServiceRequestUseCase,
            CancelServiceRequestUseCase cancelServiceRequestUseCase,
            InitiateCommentUseCase initiateCommentUseCase,
            RetrieveServiceRequestAuditLogUseCase retrieveServiceRequestAuditLogUseCase
    ) {
        this.initiateCatalogItemUseCase = initiateCatalogItemUseCase;
        this.retrieveCatalogItemUseCase = retrieveCatalogItemUseCase;
        this.retrieveCatalogItemsUseCase = retrieveCatalogItemsUseCase;
        this.updateCatalogItemUseCase = updateCatalogItemUseCase;
        this.controlCatalogItemUseCase = controlCatalogItemUseCase;
        this.retrieveCatalogAuditLogUseCase = retrieveCatalogAuditLogUseCase;
        this.initiateServiceRequestUseCase = initiateServiceRequestUseCase;
        this.retrieveServiceRequestUseCase = retrieveServiceRequestUseCase;
        this.retrieveServiceRequestsUseCase = retrieveServiceRequestsUseCase;
        this.assignServiceRequestUseCase = assignServiceRequestUseCase;
        this.captureApprovalDecisionUseCase = captureApprovalDecisionUseCase;
        this.controlServiceRequestUseCase = controlServiceRequestUseCase;
        this.fulfilServiceRequestUseCase = fulfilServiceRequestUseCase;
        this.cancelServiceRequestUseCase = cancelServiceRequestUseCase;
        this.initiateCommentUseCase = initiateCommentUseCase;
        this.retrieveServiceRequestAuditLogUseCase = retrieveServiceRequestAuditLogUseCase;
    }

    // ----------------------------------------------------------------------------------------------
    // Catalog (secondary aggregate)
    // ----------------------------------------------------------------------------------------------

    /** Behavior Qualifier: {@code catalog/initiate}. Drafts a catalog item (staff). */
    @Post("/catalog/initiate")
    public Mono<HttpResponse<CatalogItemResponse>> initiateCatalogItem(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateCatalogItemRequest request
    ) {
        log.info("[ACTION: INITIATE_CATALOG_ITEM] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiateCatalogItemUseCase.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code catalog/retrieve}. A REQUESTER sees only PUBLISHED items. */
    @Get("/catalog/{id}/retrieve")
    public Mono<HttpResponse<CatalogItemResponse>> retrieveCatalogItem(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role
    ) {
        return Mono.defer(() -> retrieveCatalogItemUseCase.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code catalog/retrieve} (collection). */
    @Get("/catalog/retrieve")
    public Mono<List<CatalogItemResponse>> retrieveCatalogItems(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
            @QueryValue @Nullable CatalogItemStatus status
    ) {
        return Mono.defer(() -> retrieveCatalogItemsUseCase.execute(UUID.fromString(tenantId), status, role).collectList());
    }

    /** Behavior Qualifier: {@code catalog/update}. Edits a DRAFT item (staff). */
    @Put("/catalog/{id}/update")
    public Mono<HttpResponse<Void>> updateCatalogItem(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdateCatalogItemRequest request
    ) {
        return Mono.defer(() -> updateCatalogItemUseCase.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code catalog/control/publish}. DRAFT -&gt; PUBLISHED. */
    @Put("/catalog/{id}/control/publish")
    public Mono<HttpResponse<Void>> publishCatalogItem(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                       @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return controlCatalog(id, tenantId, ControlCatalogItemUseCase.Action.PUBLISH, executor, role);
    }

    /** Behavior Qualifier: {@code catalog/control/retire}. PUBLISHED -&gt; RETIRED (terminal). */
    @Put("/catalog/{id}/control/retire")
    public Mono<HttpResponse<Void>> retireCatalogItem(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                      @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return controlCatalog(id, tenantId, ControlCatalogItemUseCase.Action.RETIRE, executor, role);
    }

    /** Behavior Qualifier: {@code catalog/audit-log/retrieve}. Immutable ledger of the catalog item (staff only). */
    @Get("/catalog/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> retrieveCatalogAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                                  @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveCatalogAuditLogUseCase.execute(id, UUID.fromString(tenantId), role));
    }

    // ----------------------------------------------------------------------------------------------
    // Service requests (Control Record)
    // ----------------------------------------------------------------------------------------------

    /** Behavior Qualifier: {@code initiate}. Orders a published catalog item, by staff or self-service. */
    @Post("/initiate")
    public Mono<HttpResponse<ServiceRequestResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateServiceRequestRequest request
    ) {
        log.info("[ACTION: INITIATE_SERVICE_REQUEST] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiateServiceRequestUseCase.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. A REQUESTER sees only their own request. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<ServiceRequestResponse>> retrieveById(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role
    ) {
        log.info("[ACTION: RETRIEVE_SERVICE_REQUEST] Received request to get ServiceRequest by ID: {}", id);

        return Mono.defer(() -> retrieveServiceRequestUseCase.execute(id, UUID.fromString(tenantId), executor, role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). {@code openOnly} leaves out the finished ones. */
    @Get("/retrieve")
    public Mono<List<ServiceRequestResponse>> retrieveAll(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role,
            @QueryValue @Nullable ServiceRequestStatus status, @QueryValue @Nullable UUID assigneeId, @QueryValue @Nullable UUID catalogItemId,
            @QueryValue(defaultValue = "false") boolean openOnly
    ) {
        log.info("[ACTION: RETRIEVE_SERVICE_REQUESTS] Received request to list ServiceRequests for organisation: {} status: {}", tenantId, status);

        return Mono.defer(() -> retrieveServiceRequestsUseCase
                .execute(UUID.fromString(tenantId), status, assigneeId, catalogItemId, openOnly, executor, role).collectList());
    }

    /** Behavior Qualifier: {@code assignment/update}. Names who fulfils the request (staff). */
    @Put("/{id}/assignment/update")
    public Mono<HttpResponse<Void>> assign(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid AssignServiceRequestRequest request
    ) {
        return Mono.defer(() -> assignServiceRequestUseCase.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code approval/capture}. Forwards an approver's decision; the request moves with the approval's outcome (staff). */
    @Put("/{id}/approval/capture")
    public Mono<HttpResponse<ServiceRequestResponse>> captureApproval(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid CaptureApprovalDecisionRequest request
    ) {
        return Mono.defer(() -> captureApprovalDecisionUseCase.execute(id, UUID.fromString(tenantId), request, executor, role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code control/start-fulfilment}. SUBMITTED or APPROVED -&gt; IN_FULFILMENT. */
    @Put("/{id}/control/start-fulfilment")
    public Mono<HttpResponse<Void>> controlStartFulfilment(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlServiceRequestUseCase.Action.START_FULFILMENT, executor, role);
    }

    /** Behavior Qualifier: {@code control/fulfil}. IN_FULFILMENT -&gt; FULFILLED; needs fulfilment notes. */
    @Put("/{id}/control/fulfil")
    public Mono<HttpResponse<Void>> controlFulfil(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid FulfilServiceRequestRequest request
    ) {
        return Mono.defer(() -> fulfilServiceRequestUseCase.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/close}. FULFILLED -&gt; CLOSED (terminal). */
    @Put("/{id}/control/close")
    public Mono<HttpResponse<Void>> controlClose(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                 @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlServiceRequestUseCase.Action.CLOSE, executor, role);
    }

    /** Behavior Qualifier: {@code control/cancel}. Terminal, replaces DELETE, until the request is fulfilled (staff only, ADR-031). */
    @Put("/{id}/control/cancel")
    public Mono<HttpResponse<Void>> controlCancel(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                  @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        log.info("[ACTION: CANCEL_SERVICE_REQUEST] [EXECUTOR: {}] for ID: {}", executor, id);

        return Mono.defer(() -> cancelServiceRequestUseCase.execute(id, UUID.fromString(tenantId), executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code comment/initiate}. Public, or internal for staff. */
    @Post("/{id}/comment/initiate")
    public Mono<HttpResponse<Void>> initiateComment(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateCommentRequest request
    ) {
        return Mono.defer(() -> initiateCommentUseCase.execute(id, UUID.fromString(tenantId), request, executor, role))
                .thenReturn(HttpResponse.status(HttpStatus.CREATED));
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. Immutable forensic ledger of the request (staff only). */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> retrieveAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                           @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveServiceRequestAuditLogUseCase.execute(id, UUID.fromString(tenantId), role));
    }

    private Mono<HttpResponse<Void>> controlCatalog(UUID id, String tenantId, ControlCatalogItemUseCase.Action action, String executor, String role) {
        log.info("[ACTION: CONTROL_CATALOG_ITEM] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return Mono.defer(() -> controlCatalogItemUseCase.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlServiceRequestUseCase.Action action, String executor, String role) {
        log.info("[ACTION: CONTROL_SERVICE_REQUEST] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return Mono.defer(() -> controlServiceRequestUseCase.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
