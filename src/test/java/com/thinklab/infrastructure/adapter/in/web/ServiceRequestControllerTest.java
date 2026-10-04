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
import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class ServiceRequestControllerTest {

    private static final String EXECUTOR = "op-1";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();

    @Mock private InitiateCatalogItemUseCase initiateCatalogItemUseCase;
    @Mock private RetrieveCatalogItemUseCase retrieveCatalogItemUseCase;
    @Mock private RetrieveCatalogItemsUseCase retrieveCatalogItemsUseCase;
    @Mock private UpdateCatalogItemUseCase updateCatalogItemUseCase;
    @Mock private ControlCatalogItemUseCase controlCatalogItemUseCase;
    @Mock private RetrieveCatalogAuditLogUseCase retrieveCatalogAuditLogUseCase;
    @Mock private InitiateServiceRequestUseCase initiateServiceRequestUseCase;
    @Mock private RetrieveServiceRequestUseCase retrieveServiceRequestUseCase;
    @Mock private RetrieveServiceRequestsUseCase retrieveServiceRequestsUseCase;
    @Mock private AssignServiceRequestUseCase assignServiceRequestUseCase;
    @Mock private CaptureApprovalDecisionUseCase captureApprovalDecisionUseCase;
    @Mock private ControlServiceRequestUseCase controlServiceRequestUseCase;
    @Mock private FulfilServiceRequestUseCase fulfilServiceRequestUseCase;
    @Mock private CancelServiceRequestUseCase cancelServiceRequestUseCase;
    @Mock private InitiateCommentUseCase initiateCommentUseCase;
    @Mock private RetrieveServiceRequestAuditLogUseCase retrieveServiceRequestAuditLogUseCase;

    private ServiceRequestController controller;

    @BeforeEach
    void setUp() {
        controller = new ServiceRequestController(initiateCatalogItemUseCase, retrieveCatalogItemUseCase, retrieveCatalogItemsUseCase,
                updateCatalogItemUseCase, controlCatalogItemUseCase, retrieveCatalogAuditLogUseCase, initiateServiceRequestUseCase,
                retrieveServiceRequestUseCase, retrieveServiceRequestsUseCase, assignServiceRequestUseCase, captureApprovalDecisionUseCase,
                controlServiceRequestUseCase, fulfilServiceRequestUseCase, cancelServiceRequestUseCase, initiateCommentUseCase,
                retrieveServiceRequestAuditLogUseCase);
    }

    private CatalogItemResponse sampleItem() {
        return new CatalogItemResponse(id, tenant, "LAPTOP", "New laptop", null, null, List.of(), 72, null, "DRAFT", Instant.now(), Instant.now());
    }

    private ServiceRequestResponse sampleRequest() {
        return new ServiceRequestResponse(id, tenant, UUID.randomUUID(), UUID.randomUUID(), "LAPTOP", "New laptop", Map.of(), "SUBMITTED", null, null,
                null, null, null, null, List.of(), Instant.now(), Instant.now());
    }

    // --- catalog ---

    @Test
    @DisplayName("catalog initiate answers 201 with the drafted item")
    void initiateCatalogItem() {
        var body = new InitiateCatalogItemRequest("LAPTOP", "New laptop", null, null, List.of(), 72, null);
        when(initiateCatalogItemUseCase.execute(tenant, body, EXECUTOR, "ADMIN")).thenReturn(Mono.just(sampleItem()));

        StepVerifier.create(controller.initiateCatalogItem(tenantHeader, EXECUTOR, "ADMIN", body))
                .assertNext(response -> assertEquals(HttpStatus.CREATED, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("catalog retrieve (one and all) carries tenant and role to the use case")
    void retrieveCatalog() {
        when(retrieveCatalogItemUseCase.execute(id, tenant, "REQUESTER")).thenReturn(Mono.just(sampleItem()));
        when(retrieveCatalogItemsUseCase.execute(tenant, CatalogItemStatus.PUBLISHED, null)).thenReturn(Flux.just(sampleItem(), sampleItem()));

        StepVerifier.create(controller.retrieveCatalogItem(id, tenantHeader, "REQUESTER")).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveCatalogItems(tenantHeader, null, CatalogItemStatus.PUBLISHED)).assertNext(list -> assertEquals(2, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("catalog update, publish and retire answer 204")
    void catalogCommands() {
        var update = new UpdateCatalogItemRequest("n", null, null, List.of(), 8, null);
        when(updateCatalogItemUseCase.execute(id, tenant, update, EXECUTOR, null)).thenReturn(Mono.empty());
        when(controlCatalogItemUseCase.execute(eq(id), eq(tenant), any(ControlCatalogItemUseCase.Action.class), eq(EXECUTOR), eq(null))).thenReturn(Mono.empty());

        StepVerifier.create(controller.updateCatalogItem(id, tenantHeader, EXECUTOR, null, update)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.publishCatalogItem(id, tenantHeader, EXECUTOR, null)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.retireCatalogItem(id, tenantHeader, EXECUTOR, null)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        verify(controlCatalogItemUseCase).execute(id, tenant, ControlCatalogItemUseCase.Action.PUBLISH, EXECUTOR, null);
        verify(controlCatalogItemUseCase).execute(id, tenant, ControlCatalogItemUseCase.Action.RETIRE, EXECUTOR, null);
    }

    @Test
    @DisplayName("the catalog audit log is returned as one list")
    void catalogAuditLog() {
        when(retrieveCatalogAuditLogUseCase.execute(id, tenant, "ADMIN")).thenReturn(Mono.just(List.of(new AuditEntryResponse(Instant.now(), "INITIATED", "op", null, "DRAFT", "d"))));

        StepVerifier.create(controller.retrieveCatalogAuditLog(id, tenantHeader, "ADMIN")).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    // --- requests ---

    @Test
    @DisplayName("initiate answers 201 with the request")
    void initiate() {
        var body = new InitiateServiceRequestRequest(UUID.randomUUID(), Map.of(), null);
        when(initiateServiceRequestUseCase.execute(tenant, body, EXECUTOR, "REQUESTER")).thenReturn(Mono.just(sampleRequest()));

        StepVerifier.create(controller.initiate(tenantHeader, EXECUTOR, "REQUESTER", body)).assertNext(response -> assertEquals(HttpStatus.CREATED, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("retrieve (one and all) carries tenant, executor and role to the use case")
    void retrieve() {
        UUID assignee = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        when(retrieveServiceRequestUseCase.execute(id, tenant, EXECUTOR, "REQUESTER")).thenReturn(Mono.just(sampleRequest()));
        when(retrieveServiceRequestsUseCase.execute(tenant, ServiceRequestStatus.SUBMITTED, assignee, item, true, EXECUTOR, null)).thenReturn(Flux.just(sampleRequest()));

        StepVerifier.create(controller.retrieveById(id, tenantHeader, EXECUTOR, "REQUESTER")).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveAll(tenantHeader, EXECUTOR, null, ServiceRequestStatus.SUBMITTED, assignee, item, true)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("assign, start-fulfilment, fulfil, close, cancel all answer 204")
    void commands() {
        UUID assignee = UUID.randomUUID();
        var assign = new AssignServiceRequestRequest(assignee);
        var fulfil = new FulfilServiceRequestRequest("Delivered");
        when(assignServiceRequestUseCase.execute(id, tenant, assign, EXECUTOR, null)).thenReturn(Mono.empty());
        when(fulfilServiceRequestUseCase.execute(id, tenant, fulfil, EXECUTOR, null)).thenReturn(Mono.empty());
        when(cancelServiceRequestUseCase.execute(id, tenant, EXECUTOR, "REQUESTER")).thenReturn(Mono.empty());
        when(controlServiceRequestUseCase.execute(eq(id), eq(tenant), any(ControlServiceRequestUseCase.Action.class), eq(EXECUTOR), eq(null))).thenReturn(Mono.empty());

        StepVerifier.create(controller.assign(id, tenantHeader, EXECUTOR, null, assign)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlStartFulfilment(id, tenantHeader, EXECUTOR, null)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlFulfil(id, tenantHeader, EXECUTOR, null, fulfil)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlClose(id, tenantHeader, EXECUTOR, null)).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlCancel(id, tenantHeader, EXECUTOR, "REQUESTER")).assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus())).verifyComplete();
        verify(controlServiceRequestUseCase).execute(id, tenant, ControlServiceRequestUseCase.Action.START_FULFILMENT, EXECUTOR, null);
        verify(controlServiceRequestUseCase).execute(id, tenant, ControlServiceRequestUseCase.Action.CLOSE, EXECUTOR, null);
    }

    @Test
    @DisplayName("approval capture answers 200 with the request as it is after the decision")
    void captureApproval() {
        var body = new CaptureApprovalDecisionRequest(DecisionOutcome.APPROVE, "ok");
        when(captureApprovalDecisionUseCase.execute(id, tenant, body, EXECUTOR, null)).thenReturn(Mono.just(sampleRequest()));

        StepVerifier.create(controller.captureApproval(id, tenantHeader, EXECUTOR, null, body)).assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("a comment answers 201; the request audit log is returned as one list")
    void commentAndAudit() {
        var body = new InitiateCommentRequest("hello", false);
        when(initiateCommentUseCase.execute(id, tenant, body, EXECUTOR, "REQUESTER")).thenReturn(Mono.empty());
        when(retrieveServiceRequestAuditLogUseCase.execute(id, tenant, "ADMIN")).thenReturn(Mono.just(List.of()));

        StepVerifier.create(controller.initiateComment(id, tenantHeader, EXECUTOR, "REQUESTER", body)).assertNext(response -> assertEquals(HttpStatus.CREATED, response.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveAuditLog(id, tenantHeader, "ADMIN")).assertNext(list -> assertEquals(0, list.size())).verifyComplete();
    }
}
