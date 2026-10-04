package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/** Payload for resubmitting a returned request: the answers, edited, replace the earlier ones and are checked against the item again. */
@Serdeable
public record ResubmitServiceRequestRequest(
        @Nullable
        Map<String, String> answers
) {}
