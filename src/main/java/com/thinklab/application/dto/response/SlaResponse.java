package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/**
 * The fulfilment target of a request. {@code state} is {@code PENDING} (still running, not yet late), {@code MET} or {@code BREACHED}
 * (late, whether it is still running or it finished late) - worked out when the request is read, not by a background job (ADR-034).
 */
@Serdeable
public record SlaResponse(Instant dueAt, String state) {}
