package com.thinklab.infrastructure.adapter.out.integration.workflowapproval;

import com.thinklab.domain.exception.InvalidServiceRequestStatusException;
import com.thinklab.domain.port.ApprovalServicePort.ApprovalOutcome;
import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import com.thinklab.infrastructure.adapter.out.integration.workflowapproval.WorkflowApprovalServiceAdapter.ApprovalRequestApiResponse;
import com.thinklab.infrastructure.adapter.out.integration.workflowapproval.WorkflowApprovalServiceAdapter.CaptureDecisionApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.workflowapproval.WorkflowApprovalServiceAdapter.InitiateApprovalRequestApiRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowApprovalServiceAdapterTest {

    @Mock private WorkflowApprovalApiClient apiClient;

    private WorkflowApprovalServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WorkflowApprovalServiceAdapter(apiClient);
    }

    private static void assertUnavailable(Throwable error) {
        assertInstanceOf(IllegalStateException.class, error);
        assertTrue(error.getMessage().contains("Workflow Approval Service is currently unavailable"));
    }

    @Test
    @DisplayName("initiateApprovalRequest files against the policy with subjectType='ServiceRequest' and returns the approval id")
    void initiate() {
        UUID organisationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        when(apiClient.initiate(any(), any(), any())).thenReturn(Mono.just(new ApprovalRequestApiResponse(approvalId, "PENDING")));

        StepVerifier.create(adapter.initiateApprovalRequest(organisationId, requestId, requesterId, policyId, "op-1")).expectNext(approvalId).verifyComplete();

        ArgumentCaptor<InitiateApprovalRequestApiRequest> body = ArgumentCaptor.forClass(InitiateApprovalRequestApiRequest.class);
        verify(apiClient).initiate(eq(organisationId.toString()), eq("op-1"), body.capture());
        assertEquals("ServiceRequest", body.getValue().subjectType());
        assertEquals(requestId, body.getValue().subjectId());
        assertEquals(requesterId, body.getValue().requesterId());
        assertEquals(policyId, body.getValue().policyId());
    }

    @Test
    @DisplayName("infrastructure failures are hidden behind a dependency-failure error")
    void failures() {
        when(apiClient.initiate(any(), any(), any())).thenReturn(Mono.error(new RuntimeException("refused")));
        when(apiClient.captureDecision(any(), any(), any())).thenReturn(Mono.error(new RuntimeException("refused")));
        when(apiClient.cancel(any(), any())).thenReturn(Mono.error(new RuntimeException("refused")));

        StepVerifier.create(adapter.initiateApprovalRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "op-1"))
                .expectErrorSatisfies(WorkflowApprovalServiceAdapterTest::assertUnavailable).verify();
        StepVerifier.create(adapter.captureDecision(UUID.randomUUID(), UUID.randomUUID(), DecisionOutcome.APPROVE, null, "op-1"))
                .expectErrorSatisfies(WorkflowApprovalServiceAdapterTest::assertUnavailable).verify();
        StepVerifier.create(adapter.cancelApprovalRequest(UUID.randomUUID(), "op-1"))
                .expectErrorSatisfies(WorkflowApprovalServiceAdapterTest::assertUnavailable).verify();
    }

    @Test
    @DisplayName("captureDecision forwards the outcome and comment and maps the resolved status back")
    void capture() {
        UUID approvalId = UUID.randomUUID();
        when(apiClient.captureDecision(eq(approvalId), eq("approver-1"), any())).thenReturn(Mono.just(new ApprovalRequestApiResponse(approvalId, "APPROVED")));

        StepVerifier.create(adapter.captureDecision(approvalId, UUID.randomUUID(), DecisionOutcome.APPROVE, "fine", "approver-1"))
                .expectNext(ApprovalOutcome.APPROVED).verifyComplete();

        ArgumentCaptor<CaptureDecisionApiRequest> body = ArgumentCaptor.forClass(CaptureDecisionApiRequest.class);
        verify(apiClient).captureDecision(eq(approvalId), eq("approver-1"), body.capture());
        assertEquals("APPROVE", body.getValue().outcome());
        assertEquals("fine", body.getValue().comment());
    }

    @Test
    @DisplayName("a 409 on a decision (an approver who is not eligible) is relayed as a 409 with the reason; without a body it falls back to the message; other statuses stay dependency failures")
    void conflictIsRelayed() {
        var withBody = new HttpClientResponseException("Conflict", HttpResponse.status(HttpStatus.CONFLICT).body(Map.of("detail", "approver is not eligible")));
        var withoutBody = new HttpClientResponseException("Conflict without body", HttpResponse.status(HttpStatus.CONFLICT));
        var notFound = new HttpClientResponseException("Not Found", HttpResponse.status(HttpStatus.NOT_FOUND));
        when(apiClient.captureDecision(any(), any(), any())).thenReturn(Mono.error(withBody)).thenReturn(Mono.error(withoutBody)).thenReturn(Mono.error(notFound));

        StepVerifier.create(adapter.captureDecision(UUID.randomUUID(), UUID.randomUUID(), DecisionOutcome.APPROVE, null, "op-1"))
                .expectErrorSatisfies(error -> { assertInstanceOf(InvalidServiceRequestStatusException.class, error); assertEquals("approver is not eligible", error.getMessage()); }).verify();
        StepVerifier.create(adapter.captureDecision(UUID.randomUUID(), UUID.randomUUID(), DecisionOutcome.APPROVE, null, "op-1"))
                .expectErrorSatisfies(error -> { assertInstanceOf(InvalidServiceRequestStatusException.class, error); assertEquals("Conflict without body", error.getMessage()); }).verify();
        StepVerifier.create(adapter.captureDecision(UUID.randomUUID(), UUID.randomUUID(), DecisionOutcome.APPROVE, null, "op-1"))
                .expectErrorSatisfies(WorkflowApprovalServiceAdapterTest::assertUnavailable).verify();
    }

    @Test
    @DisplayName("cancelApprovalRequest withdraws the approval request")
    void cancel() {
        UUID approvalId = UUID.randomUUID();
        when(apiClient.cancel(approvalId, "op-1")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.cancelApprovalRequest(approvalId, "op-1")).verifyComplete();
        verify(apiClient).cancel(approvalId, "op-1");
    }
}
