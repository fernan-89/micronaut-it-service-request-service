# ADR-035: An Approver Can Return a Request, and a Requester Can Cancel Their Own

## Status
Accepted (refines ADR-031 and ADR-032)

## Context
Two limits of the first slice hurt in practice. A requester who changed their mind had to ask IT to cancel (ADR-031 kept every cancel a staff action). And an approver who found a request incomplete could only approve or reject it: a rejection ends the request and the requester starts over without knowing what to fix.

## Decision
- **Return.** `PUT /{id}/approval/capture` accepts a third outcome, `RETURN`, which needs a **comment** saying what to fix (blank is 400, refused before workflow-approval is asked). workflow-approval resolves its approval request to `RETURNED` (its ADR-035) and this service moves the request `PENDING_APPROVAL -> RETURNED`, keeping the comment as `returnReason` (visible to the requester). `RETURNED` is not terminal here: it still counts against the SLA (the clock runs, ADR-034), can take comments and an assignee, and can be cancelled.
- **Resubmit.** `PUT /{id}/control/resubmit {answers}` takes a `RETURNED` request back to `PENDING_APPROVAL`. The requester (own request only, another's is 404) or staff replace the answers, which are checked against the item again; the item must still be PUBLISHED. A **new approval request is filed first** on workflow-approval - a fresh chain from stage one, because the content changed - and the request is saved with a guard on `RETURNED`, so a cancel racing the resubmit is not overwritten. The earlier approval stays on workflow-approval as `RETURNED`, and this service's audit trail keeps the whole story (`INITIATED, RETURNED, RESUBMITTED, APPROVED`). The fulfilment due date does not move. If the save loses the race, the new approval request is an orphan (the same cost as for creation, ADR-032).
- **Cancel by the requester.** A REQUESTER may cancel **their own** request until it is fulfilled (SUBMITTED, PENDING_APPROVAL, RETURNED, APPROVED, IN_FULFILMENT); another's request answers 404. A pending approval is withdrawn as before (best effort). Every other staff action stays 403 for a requester.

## Consequences
- Positive: a request that is merely incomplete is corrected in place instead of rejected and retyped; the approver's reason travels with the request; people can withdraw what they no longer need.
- Negative: a requester can cancel a request IT has already started (the work in progress is simply abandoned); resubmitting restarts approval from stage one, including stages that had already approved (deliberate, the content changed); editing is limited to the answers (not the item) and only after a return - there is no free editing of a request that is being approved.
