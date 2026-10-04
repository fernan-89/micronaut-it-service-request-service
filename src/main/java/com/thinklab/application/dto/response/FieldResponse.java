package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record FieldResponse(String key, String label, boolean required) {}
