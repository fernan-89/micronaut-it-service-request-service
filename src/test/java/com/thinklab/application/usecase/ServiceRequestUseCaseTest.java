package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.AssignServiceRequestRequest;
import com.thinklab.application.dto.request.CaptureApprovalDecisionRequest;
import com.thinklab.application.dto.request.FulfilServiceRequestRequest;
import com.thinklab.application.dto.request.InitiateCommentRequest;
import com.thinklab.application.dto.request.InitiateServiceRequestRequest;
import com.thinklab.domain.exception.CatalogItemNotFoundException;
import com.thinklab.domain.exception.InvalidCatalogItemStatusException;
import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.exception.ServiceRequestAccessDeniedException;
import com.thinklab.domain.exception.ServiceRequestNotFoundException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.domain.model.ServiceRequest;
import com.thinklab.domain.model.ServiceRequest.Comment;
import com.thinklab.domain.model.ServiceRequest.ServiceRequestStatus;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.port.ApprovalServicePort.ApprovalOutcome;
import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CatalogItemRepository;
import com.thinklab.domain.repository.ServiceRequestRepository;
import com.thinklab.domain.repository.ServiceRequestRepository.Filter;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceRequestUseCaseTest {

    private static final String REQUESTER = "REQUESTER";

    @Mock private ServiceRequestRepository requestRepository;
    @Mock private CatalogItemRepository catalogRepository;
    @Mock private HashServicePort hashService;
    @Mock private ApprovalServicePort approvalService;

    private final UUID org = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private RequestWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new RequestWorkflow(requestRepository);
    }

    private CatalogItem item(UUID policy, boolean publish) {
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), org, "LAPTOP", "New laptop", null, "HARDWARE",
                List.of(new Field("model", "Which model?", true)), 72, policy, "op-1");
        if (publish) {
            item.publish("op-1");
        }
        return item;
    }

    private ServiceRequest submitted() {
        return ServiceRequest.createNew(UUID.randomUUID(), org, owner, item(null, true), Map.of("model", "X1"), null, "req");
    }

    private ServiceRequest pending() {
        return ServiceRequest.createNew(UUID.randomUUID(), org, owner, item(UUID.randomUUID(), true), Map.of("model", "X1"), UUID.randomUUID(), "req");
    }

    private ServiceRequest found(ServiceRequest request) {
        when(requestRepository.findById(request.getId(), org)).thenReturn(Mono.just(request));
        return request;
    }

    private UUID missing() {
        UUID unknown = UUID.randomUUID();
        when(requestRepository.findById(unknown, org)).thenReturn(Mono.empty());
        return unknown;
    }

    // --- initiate ---

    private InitiateServiceRequestUseCase initiateUseCase() {
        return new InitiateServiceRequestUseCase(hashService, catalogRepository, requestRepository, approvalService);
    }

    @Test
    @DisplayName("staff order on someone's behalf; an item without a policy needs no approval and the request is SUBMITTED")
    void initiateByStaff() {
        CatalogItem item = item(null, true);
        UUID requestId = UUID.randomUUID();
        when(catalogRepository.findById(item.getId(), org)).thenReturn(Mono.just(item));
        when(hashService.generateSovereignId("service-request-creation")).thenReturn(Mono.just(requestId));
        when(requestRepository.create(any(ServiceRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(initiateUseCase().execute(org, new InitiateServiceRequestRequest(item.getId(), Map.of("model", "X1"), owner), "op-1", null))
                .assertNext(created -> {
                    assertEquals(requestId, created.id());
                    assertEquals(owner, created.requesterId());
                    assertEquals("SUBMITTED", created.status());
                })
                .verifyComplete();
        verifyNoInteractions(approvalService);
    }

    @Test
    @DisplayName("a REQUESTER orders for themselves (the executor id), whatever requesterId says")
    void initiateByRequester() {
        CatalogItem item = item(null, true);
        when(catalogRepository.findById(item.getId(), org)).thenReturn(Mono.just(item));
        when(hashService.generateSovereignId("service-request-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(requestRepository.create(any(ServiceRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(initiateUseCase().execute(org, new InitiateServiceRequestRequest(item.getId(), Map.of("model", "X1"), UUID.randomUUID()), owner.toString(), REQUESTER))
                .assertNext(created -> assertEquals(owner, created.requesterId()))
                .verifyComplete();
    }

    @Test
    @DisplayName("an item with an approval policy files the approval first and the request starts PENDING_APPROVAL carrying its id")
    void initiateWithApproval() {
        UUID policy = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        CatalogItem item = item(policy, true);
        when(catalogRepository.findById(item.getId(), org)).thenReturn(Mono.just(item));
        when(hashService.generateSovereignId("service-request-creation")).thenReturn(Mono.just(requestId));
        when(approvalService.initiateApprovalRequest(org, requestId, owner, policy, "op-1")).thenReturn(Mono.just(approvalId));
        when(requestRepository.create(any(ServiceRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(initiateUseCase().execute(org, new InitiateServiceRequestRequest(item.getId(), Map.of("model", "X1"), owner), "op-1", null))
                .assertNext(created -> {
                    assertEquals("PENDING_APPROVAL", created.status());
                    assertEquals(approvalId, created.approvalRequestId());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("if the approval cannot be filed nothing is created")
    void initiateApprovalFails() {
        UUID policy = UUID.randomUUID();
        CatalogItem item = item(policy, true);
        when(catalogRepository.findById(item.getId(), org)).thenReturn(Mono.just(item));
        when(hashService.generateSovereignId("service-request-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(approvalService.initiateApprovalRequest(any(), any(), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(initiateUseCase().execute(org, new InitiateServiceRequestRequest(item.getId(), Map.of("model", "X1"), owner), "op-1", null))
                .expectError(IllegalStateException.class).verify();
        verify(requestRepository, never()).create(any());
    }

    @Test
    @DisplayName("initiate refuses: staff without requesterId, an unknown item, an unpublished item, bad answers (before any id is spent)")
    void initiateRefusals() {
        CatalogItem published = item(null, true);
        CatalogItem draft = item(null, false);
        UUID unknown = UUID.randomUUID();
        when(catalogRepository.findById(published.getId(), org)).thenReturn(Mono.just(published));
        when(catalogRepository.findById(draft.getId(), org)).thenReturn(Mono.just(draft));
        when(catalogRepository.findById(unknown, org)).thenReturn(Mono.empty());
        InitiateServiceRequestUseCase useCase = initiateUseCase();

        StepVerifier.create(useCase.execute(org, new InitiateServiceRequestRequest(published.getId(), Map.of("model", "X1"), null), "op-1", null))
                .expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(org, new InitiateServiceRequestRequest(unknown, Map.of(), owner), "op-1", null))
                .expectError(CatalogItemNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(org, new InitiateServiceRequestRequest(draft.getId(), Map.of("model", "X1"), owner), "op-1", null))
                .expectError(InvalidCatalogItemStatusException.class).verify();
        StepVerifier.create(useCase.execute(org, new InitiateServiceRequestRequest(published.getId(), Map.of("colour", "red"), owner), "op-1", null))
                .expectError(IllegalArgumentException.class).verify();
        verifyNoInteractions(hashService, approvalService);
    }

    // --- retrieve ---

    @Test
    @DisplayName("retrieve: the owner and staff see a request, another requester gets a 404, an unknown id a 404; a requester never sees internal notes")
    void retrieve() {
        ServiceRequest request = found(submitted());
        request.addComment(new Comment(UUID.randomUUID(), "op", "private", true, null), "op");
        UUID unknownId = missing();
        RetrieveServiceRequestUseCase useCase = new RetrieveServiceRequestUseCase(requestRepository);

        StepVerifier.create(useCase.execute(request.getId(), org, owner.toString(), REQUESTER))
                .assertNext(response -> assertTrue(response.comments().isEmpty())).verifyComplete();
        StepVerifier.create(useCase.execute(request.getId(), org, "op-1", null))
                .assertNext(response -> assertEquals(1, response.comments().size())).verifyComplete();
        StepVerifier.create(useCase.execute(request.getId(), org, UUID.randomUUID().toString(), REQUESTER))
                .expectError(ServiceRequestNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(unknownId, org, "op-1", null)).expectError(ServiceRequestNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieve all: a REQUESTER is narrowed to their own requests, staff are not")
    void retrieveAll() {
        when(requestRepository.findAll(eq(org), any(Filter.class))).thenReturn(Flux.just(submitted()));
        RetrieveServiceRequestsUseCase useCase = new RetrieveServiceRequestsUseCase(requestRepository);
        UUID assignee = UUID.randomUUID();
        UUID catalogItem = UUID.randomUUID();

        StepVerifier.create(useCase.execute(org, ServiceRequestStatus.SUBMITTED, assignee, catalogItem, true, owner.toString(), REQUESTER)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(org, null, null, null, false, "op-1", null)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        verify(requestRepository, times(2)).findAll(eq(org), filter.capture());
        assertEquals(new Filter(ServiceRequestStatus.SUBMITTED, assignee, catalogItem, owner, true), filter.getAllValues().get(0));
        assertNull(filter.getAllValues().get(1).requesterId());
    }

    // --- staff transitions through the workflow ---

    @Test
    @DisplayName("assign, start-fulfilment, fulfil and close each go through a guarded save with the status the request was loaded in")
    void staffTransitions() {
        ServiceRequest request = found(submitted());
        when(requestRepository.save(any(), any(), any())).thenReturn(Mono.empty());
        UUID assignee = UUID.randomUUID();

        StepVerifier.create(new AssignServiceRequestUseCase(workflow).execute(request.getId(), org, new AssignServiceRequestRequest(assignee), "op-1", null)).verifyComplete();
        assertEquals(assignee, request.getAssigneeId());
        ControlServiceRequestUseCase control = new ControlServiceRequestUseCase(workflow);
        StepVerifier.create(control.execute(request.getId(), org, ControlServiceRequestUseCase.Action.START_FULFILMENT, "op-1", null)).verifyComplete();
        verify(requestRepository, atLeastOnce()).save(eq(request), eq(ServiceRequestStatus.SUBMITTED), any());
        StepVerifier.create(new FulfilServiceRequestUseCase(workflow).execute(request.getId(), org, new FulfilServiceRequestRequest("Delivered"), "op-1", null)).verifyComplete();
        verify(requestRepository).save(eq(request), eq(ServiceRequestStatus.IN_FULFILMENT), any());
        StepVerifier.create(control.execute(request.getId(), org, ControlServiceRequestUseCase.Action.CLOSE, "op-1", null)).verifyComplete();
        verify(requestRepository).save(eq(request), eq(ServiceRequestStatus.FULFILLED), any());
        assertEquals(ServiceRequestStatus.CLOSED, request.getStatus());
    }

    @Test
    @DisplayName("staff actions refuse a REQUESTER, an unknown request and an illegal transition")
    void staffRefusals() {
        ServiceRequest request = found(submitted());
        UUID unknownId = missing();
        ControlServiceRequestUseCase control = new ControlServiceRequestUseCase(workflow);

        StepVerifier.create(control.execute(request.getId(), org, ControlServiceRequestUseCase.Action.CLOSE, "op-1", REQUESTER)).expectError(ServiceRequestAccessDeniedException.class).verify();
        StepVerifier.create(control.execute(unknownId, org, ControlServiceRequestUseCase.Action.CLOSE, "op-1", null)).expectError(ServiceRequestNotFoundException.class).verify();
        StepVerifier.create(control.execute(request.getId(), org, ControlServiceRequestUseCase.Action.CLOSE, "op-1", null)).expectError(InvalidServiceRequestStatusException.class).verify();
        verify(requestRepository, never()).save(any(), any(), any());
    }

    // --- cancel ---

    @Test
    @DisplayName("cancelling a request that waits for approval also withdraws the approval; cancelling any other does not touch it")
    void cancel() {
        ServiceRequest waiting = found(pending());
        ServiceRequest plain = found(submitted());
        when(requestRepository.save(any(), any(), any())).thenReturn(Mono.empty());
        when(approvalService.cancelApprovalRequest(waiting.getApprovalRequestId(), "op-1")).thenReturn(Mono.empty());
        CancelServiceRequestUseCase useCase = new CancelServiceRequestUseCase(requestRepository, approvalService);

        StepVerifier.create(useCase.execute(waiting.getId(), org, "op-1", null)).verifyComplete();
        verify(approvalService).cancelApprovalRequest(waiting.getApprovalRequestId(), "op-1");
        StepVerifier.create(useCase.execute(plain.getId(), org, "op-1", null)).verifyComplete();
        verify(approvalService, times(1)).cancelApprovalRequest(any(), any());
        assertEquals(ServiceRequestStatus.CANCELLED, plain.getStatus());
    }

    @Test
    @DisplayName("if the approval cannot be withdrawn the cancellation still stands")
    void cancelBestEffort() {
        ServiceRequest waiting = found(pending());
        when(requestRepository.save(any(), any(), any())).thenReturn(Mono.empty());
        when(approvalService.cancelApprovalRequest(any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(new CancelServiceRequestUseCase(requestRepository, approvalService).execute(waiting.getId(), org, "op-1", null)).verifyComplete();
        assertEquals(ServiceRequestStatus.CANCELLED, waiting.getStatus());
    }

    @Test
    @DisplayName("cancel refuses a REQUESTER and an unknown request")
    void cancelRefusals() {
        UUID unknownId = missing();
        CancelServiceRequestUseCase useCase = new CancelServiceRequestUseCase(requestRepository, approvalService);

        StepVerifier.create(useCase.execute(UUID.randomUUID(), org, owner.toString(), REQUESTER)).expectError(ServiceRequestAccessDeniedException.class).verify();
        StepVerifier.create(useCase.execute(unknownId, org, "op-1", null)).expectError(ServiceRequestNotFoundException.class).verify();
    }

    // --- approval capture ---

    private Mono<?> capture(ServiceRequest request, ApprovalOutcome outcome, String role) {
        when(approvalService.captureDecision(any(), any(), any(), any(), any())).thenReturn(Mono.just(outcome));
        return new CaptureApprovalDecisionUseCase(requestRepository, approvalService)
                .execute(request.getId(), org, new CaptureApprovalDecisionRequest(DecisionOutcome.APPROVE, "ok"), UUID.randomUUID().toString(), role);
    }

    @Test
    @DisplayName("an APPROVED outcome moves the request to APPROVED, a REJECTED one to REJECTED, a still PENDING one leaves it waiting")
    void captureOutcomes() {
        when(requestRepository.save(any(), any(), any())).thenReturn(Mono.empty());
        ServiceRequest approved = found(pending());
        ServiceRequest rejected = found(pending());
        ServiceRequest waiting = found(pending());

        StepVerifier.create(capture(approved, ApprovalOutcome.APPROVED, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(capture(rejected, ApprovalOutcome.REJECTED, "ADMIN")).expectNextCount(1).verifyComplete();
        StepVerifier.create(capture(waiting, ApprovalOutcome.PENDING, null)).expectNextCount(1).verifyComplete();

        assertEquals(ServiceRequestStatus.APPROVED, approved.getStatus());
        assertEquals(ServiceRequestStatus.REJECTED, rejected.getStatus());
        assertEquals(ServiceRequestStatus.PENDING_APPROVAL, waiting.getStatus());
        verify(requestRepository, times(2)).save(any(), eq(ServiceRequestStatus.PENDING_APPROVAL), any());
    }

    @Test
    @DisplayName("capture refuses a REQUESTER, an unknown request and a request that is not waiting for approval")
    void captureRefusals() {
        UUID unknownId = missing();
        ServiceRequest notWaiting = found(submitted());
        CaptureApprovalDecisionUseCase useCase = new CaptureApprovalDecisionUseCase(requestRepository, approvalService);
        var body = new CaptureApprovalDecisionRequest(DecisionOutcome.REJECT, null);
        String approver = UUID.randomUUID().toString();

        StepVerifier.create(useCase.execute(notWaiting.getId(), org, body, approver, REQUESTER)).expectError(ServiceRequestAccessDeniedException.class).verify();
        StepVerifier.create(useCase.execute(unknownId, org, body, approver, null)).expectError(ServiceRequestNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(notWaiting.getId(), org, body, approver, null)).expectError(InvalidServiceRequestStatusException.class).verify();
        verifyNoInteractions(approvalService);
    }

    // --- comments & audit ---

    @Test
    @DisplayName("a REQUESTER comments on their own request, publicly even if they ask for internal; staff may write internal notes")
    void comment() {
        ServiceRequest request = found(submitted());
        when(hashService.generateSovereignId("service-request-comment-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(requestRepository.addComment(any(), any(), any(), any())).thenReturn(Mono.empty());
        InitiateCommentUseCase useCase = new InitiateCommentUseCase(hashService, requestRepository);

        StepVerifier.create(useCase.execute(request.getId(), org, new InitiateCommentRequest("hello", true), owner.toString(), REQUESTER)).verifyComplete();
        StepVerifier.create(useCase.execute(request.getId(), org, new InitiateCommentRequest("note", true), "op-1", null)).verifyComplete();

        ArgumentCaptor<Comment> comment = ArgumentCaptor.forClass(Comment.class);
        verify(requestRepository, times(2)).addComment(eq(request.getId()), eq(org), comment.capture(), any());
        assertEquals(false, comment.getAllValues().get(0).internal());
        assertEquals(true, comment.getAllValues().get(1).internal());
    }

    @Test
    @DisplayName("a REQUESTER cannot comment on someone else's request, and an unknown request is a 404")
    void commentRefusals() {
        ServiceRequest request = found(submitted());
        UUID unknownId = missing();
        InitiateCommentUseCase useCase = new InitiateCommentUseCase(hashService, requestRepository);
        var body = new InitiateCommentRequest("hello", false);

        StepVerifier.create(useCase.execute(request.getId(), org, body, UUID.randomUUID().toString(), REQUESTER)).expectError(ServiceRequestNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(unknownId, org, body, "op-1", null)).expectError(ServiceRequestNotFoundException.class).verify();
        verifyNoInteractions(hashService);
    }

    @Test
    @DisplayName("the audit log is for staff; an unknown request is a 404")
    void auditLog() {
        ServiceRequest request = found(submitted());
        UUID unknownId = missing();
        RetrieveServiceRequestAuditLogUseCase useCase = new RetrieveServiceRequestAuditLogUseCase(requestRepository);

        StepVerifier.create(useCase.execute(request.getId(), org, null)).assertNext(log -> assertEquals(1, log.size())).verifyComplete();
        StepVerifier.create(useCase.execute(request.getId(), org, REQUESTER)).expectError(ServiceRequestAccessDeniedException.class).verify();
        StepVerifier.create(useCase.execute(unknownId, org, "op-1")).expectError(ServiceRequestNotFoundException.class).verify();
    }
}
