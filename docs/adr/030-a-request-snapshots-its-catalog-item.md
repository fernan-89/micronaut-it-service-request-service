# ADR-030: A Request Snapshots Its Catalog Item

## Status
Accepted

## Context
A catalog item defines what can be asked for: the questions a requester answers, how long fulfilment may take and whether approval is needed first. Items change over time (a new question, a longer target) and are eventually retired. If a request only pointed at its item, editing or retiring the item would silently change - or break - every request already in flight, and an SLA judged against a moving target is meaningless.

## Decision
- A `ServiceRequest` copies what it needs from its `CatalogItem` **when it is made**: the item's code and name, and the fulfilment due date (`createdAt + fulfilmentTargetHours`). The answers are validated against the item's fields at that moment (unknown key, missing mandatory answer and over-long answer are refused with 400).
- A `CatalogItem` follows `DRAFT -> PUBLISHED -> RETIRED`. It is editable **only while DRAFT** (a published item is a promise to the people who request it), only a PUBLISHED item can be requested, and a RETIRED item keeps serving requests already made but is no longer offered. There is no `DELETE`.
- The item's `approvalPolicyId` is read when the request is made and decides the starting status; it is not copied (the approval request id is what the request carries).

## Consequences
- Positive: editing or retiring the catalog never changes a request in flight; the SLA of a request is stable and explainable.
- Negative: to change a published item you retire it and publish a new one under a new code (the code is unique per organisation, and a retired code is not reusable). Corrections to a typo in a published item are not possible in place.
