package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record CommentResponse(UUID commentId, String author, String text, boolean internal, Instant createdAt) {}
