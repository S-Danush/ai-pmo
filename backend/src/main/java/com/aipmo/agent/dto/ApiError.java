package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Error payload for {@link ApiResponse} when {@code success} is false. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String message, Integer status) {}
