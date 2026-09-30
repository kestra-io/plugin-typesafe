package io.kestra.plugin.typesafe;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Vendor DTO for the TypeSafe {@code POST /v1/systemone} response body.
 *
 * <p>Kept separate from the Kestra-facing output models; unknown fields are ignored so the
 * plugin does not break when the API adds new fields.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
class TypeSafeResponse {
    /**
     * The versioned model id that performed the evaluation (e.g. {@code jev-1.13.0}).
     */
    private String model;

    /**
     * One answer per question, keyed by the caller-chosen question ids.
     */
    private Map<String, Answer> answers;

    private Usage usage;

    /**
     * Vendor DTO for the {@code usage} response object.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Usage {
        @JsonProperty("input_tokens")
        private long inputTokens;

        @JsonProperty("output_tokens")
        private long outputTokens;
    }
}
