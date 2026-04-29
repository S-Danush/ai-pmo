package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard API envelope for JSON responses: {@code success}, {@code data}, {@code error}.
 *
 * @param <T> payload type when successful
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message, int status) {
        return new ApiResponse<>(false, null, new ApiError(message, status));
    }
}
