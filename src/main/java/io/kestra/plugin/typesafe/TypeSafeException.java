package io.kestra.plugin.typesafe;

import lombok.Getter;

/**
 * Failure of a TypeSafe API call after rendering, validation and (where applicable) retries.
 *
 * <p>Carries the HTTP status code when the failure came from an API response
 * ({@code -1} when there was no HTTP response, e.g. network errors).
 */
@Getter
public class TypeSafeException extends Exception {
    private final int statusCode;

    public TypeSafeException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public TypeSafeException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public TypeSafeException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public TypeSafeException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
