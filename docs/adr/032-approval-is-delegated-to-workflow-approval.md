# ADR-032: Approval Is Delegated to workflow-approval-service

## Status
Accepted

## Context
Some catalog items ("a software seat", "admin access") need approval before anyone fulfils them. Approval - who may approve, how many stages, segregation of duties, the inbox - is a shared capability that `workflow-approval-service` already owns (ADR-033/034 there: ordered stages, quorum, segregation of duties). Rebuilding any of it here would fork the rules.

## Decision
- A catalog item may name an `approvalPolicyId`. This service never decides who may approve.
- **Approval is filed first.** When a request is made for such an item, the approval request is filed on workflow-approval (`POST /initiate`, `subjectType = "ServiceRequest"`) **before** the request is saved: a request that needs approval must never exist without one. If filing fails the call fails and nothing is created (the cost is, at worst, an orphan approval request that no one will ever see in a request list).
- The request starts `PENDING_APPROVAL` and carries the approval request id. The approver's decision is forwarded with `PUT /{id}/approval/capture` (staff only; the approver is the `X-Executor`, as workflow-approval requires). The outcome is read back in the same call: `APPROVED` moves the request to APPROVED, `REJECTED` to REJECTED (terminal), and a still `PENDING` outcome (a chain with stages left, a quorum not reached) leaves it waiting. The integration is synchronous HTTP: no events, nothing to reconcile.
- **Cancelling** a request that is waiting for approval also withdraws the approval request, so it leaves the approvers' inboxes. That withdrawal is **best effort**: the cancellation stands even if workflow-approval is down (an approval left behind is harmless, since its decision can no longer move a cancelled request).
- A failure of workflow-approval surfaces as `500 ERR-INTERNAL-00500` with a "Dependency Failure" message, the same as in change management.

## Consequences
- Positive: chains, quorum and segregation of duties work for requests with no code here; this service holds no approval logic.
- Negative: a request that needs approval cannot be made while workflow-approval is down; an approver must decide through this service's route for the request to move (a decision made directly on workflow-approval is not noticed until someone captures through here), which is the same coupling as `ChangeRequest`.
