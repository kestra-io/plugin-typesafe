package io.kestra.plugin.typesafe;

import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.exceptions.KilledException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientRequestException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.RetryUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Shared base for the TypeSafe tasks: authentication, request building, status-dispatched
 * errors, retries with {@code retry-after} support, and cooperative cancellation.
 */
@SuperBuilder
@Getter
@NoArgsConstructor
public abstract class AbstractTypeSafe extends Task {
    protected static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    protected static final String DEFAULT_MODEL = "jev-latest";

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 529);

    /** Initial attempt plus 3 retries. */
    private static final int MAX_ATTEMPTS = 4;

    /** Upper bound for a single server-directed {@code retry-after} wait. */
    private static final long MAX_RETRY_AFTER_WAIT_MS = 60_000L;
    private static final long RETRY_AFTER_POLL_MS = 100L;

    /**
     * Note on retry layering (verified against Kestra 1.3.39 / Apache HttpClient 5.6):
     * Kestra's {@code HttpClient} does not expose a retry-strategy setting, and the underlying
     * Apache client transparently retries HTTP 429 and 503 responses once (waiting ~1s and
     * honoring the {@code Retry-After} header itself). That inner retry is invisible to this
     * class: the {@code RetryUtils} policy above applies on top, so a persistently rate-limited
     * call can issue up to {@code MAX_ATTEMPTS x 2} HTTP requests for 429s. HTTP 401, 422 and 529
     * are not retried by the Apache layer (verified by tests); all of their retries, if any,
     * come from this class.
     */

    @Schema(
        title = "TypeSafe API key",
        description = "Bearer token sent in the `Authorization` header; store it as a secret."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> apiKey;

    @Schema(
        title = "TypeSafe base URL",
        description = "Base URL of the TypeSafe API. Override it to target a mock server in tests."
    )
    @PluginProperty(group = "connection")
    @lombok.Builder.Default
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "Model",
        description = "The model that handles the request, e.g. `jev-latest`. The response reports the versioned model id that answered."
    )
    @PluginProperty(group = "main")
    @lombok.Builder.Default
    protected Property<String> model = Property.ofValue(DEFAULT_MODEL);

    @Schema(
        title = "Questions",
        description = "A map of typed questions, keyed by an id you choose; answers come back under the same ids."
    )
    @NotNull
    @PluginProperty(
        group = "main",
        additionalProperties = Question.class
    )
    protected Property<Map<String, Question>> questions;

    @Getter(AccessLevel.NONE)
    private final AtomicBoolean killed = new AtomicBoolean(false);

    @Getter(AccessLevel.NONE)
    private final AtomicReference<HttpClient> activeClient = new AtomicReference<>();

    @Getter(AccessLevel.NONE)
    private volatile Thread runThread;

    /**
     * Cancels in-flight work. Satisfies {@link io.kestra.core.models.WorkerJobLifecycle#kill()}
     * for subclasses implementing {@code RunnableTask}; declared here so cancellation resources
     * can be shared.
     *
     * <p>Note: There is a small race window between {@code trackRunThread()} and the actual
     * work starting where {@code kill()} might miss the thread or interrupt a stale reference.
     * However, this is mitigated by: (1) the race window is extremely small (between
     * {@code trackRunThread()} and actual work start), (2) {@code isKilled()} also checks
     * {@code Thread.currentThread().isInterrupted()}, and (3) {@code checkKilled()} throws
     * {@code KilledException} which is caught by the task's exception handling. Given the
     * narrow window and existing safeguards, this is considered an acceptable risk without
     * adding complex synchronization.
     */
    public void kill() {
        killed.set(true);
        Thread thread = runThread;
        if (thread != null) {
            thread.interrupt();
        }
        releaseClient();
        onKill();
    }

    /**
     * Extension point for subclasses holding additional cancellable resources (e.g. executors).
     */
    protected void onKill() {
        // no-op by default
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    protected boolean isKilled() {
        return killed.get() || Thread.currentThread().isInterrupted();
    }

    protected void checkKilled() {
        if (isKilled()) {
            throw new KilledException("TypeSafe task was killed.");
        }
    }

    protected void trackRunThread() {
        runThread = Thread.currentThread();
    }

    protected void untrackRunThread() {
        runThread = null;
    }

    protected HttpClient newClient(RunContext runContext) throws IllegalVariableEvaluationException {
        HttpClient client = new HttpClient(
            runContext,
            HttpConfiguration.builder()
                .timeout(
                    TimeoutConfiguration.builder()
                        .connectTimeout(Property.ofValue(Duration.ofSeconds(30)))
                        .readIdleTimeout(Property.ofValue(Duration.ofSeconds(120)))
                        .build()
                )
                .build()
        );
        activeClient.set(client);
        return client;
    }

    protected void releaseClient() {
        HttpClient client = activeClient.getAndSet(null);
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                // best effort on cancellation paths
            }
        }
    }

    /**
     * Validates rendered question definitions before any HTTP call is made.
     *
     * @throws IllegalArgumentException if the map is empty or any question is invalid
     */
    protected void validateQuestions(Map<String, Question> rendered) {
        if (rendered == null || rendered.isEmpty()) {
            throw new IllegalArgumentException("Invalid questions: at least one question is required. Fix: add one or more entries to 'questions'.");
        }
        rendered.forEach((id, question) -> {
            if (question == null) {
                throw new IllegalArgumentException("Invalid question '" + id + "': the question definition is empty. Fix: set 'type' and 'instructions' for '" + id + "'.");
            }
            question.validate(id);
        });
    }

    /**
     * Evaluates one state against the given questions with retries and status-dispatched errors.
     *
     * @return the parsed API response
     * @throws TypeSafeException on API errors, exhausted retries, or malformed responses
     */
    protected TypeSafeResponse evaluateState(
        RunContext runContext,
        HttpClient client,
        String baseUrl,
        String apiKey,
        String model,
        Object state,
        Map<String, Question> questions
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("model", model);
        Map<String, Object> apiQuestions = new LinkedHashMap<>();
        questions.forEach((id, question) -> apiQuestions.put(id, question.toApiMap()));
        body.put("questions", apiQuestions);

        HttpRequest request = HttpRequest.builder()
            .uri(URI.create(normalizeBaseUrl(baseUrl) + "/v1/systemone"))
            .method("POST")
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .body(
                HttpRequest.StringRequestBody.builder()
                    .contentType("application/json")
                    .charset(java.nio.charset.StandardCharsets.UTF_8)
                    .content(serializeRequestBody(body))
                    .build()
            )
            .build();

        runContext.logger().debug("Evaluating {} question(s) with model '{}'.", questions.size(), model);

        // All retries share a single MAX_ATTEMPTS budget: server-directed Retry-After
        // waits are handled inside executeRequestWithRetryAfterHandling while other
        // retryable errors go through RetryUtils exponential backoff. The shared
        // totalAttempts counter guarantees at most four total HTTP attempts.
        AtomicInteger totalAttempts = new AtomicInteger(0);
        HttpResponse<String> response;
        try {
            response = RetryUtils.<HttpResponse<String>, Exception>of(retryPolicy(), runContext.logger())
                .run((res, err) -> totalAttempts.get() < MAX_ATTEMPTS && isRetryableWithoutRetryAfter(err), () -> {
                    return executeRequestWithRetryAfterHandling(runContext, client, request, totalAttempts);
                });
        } catch (RetryUtils.RetryFailed e) {
            throw exhausted(e);
        } catch (HttpClientResponseException e) {
            // Retry-After exhaustion already consumed the shared budget inside
            // executeRequestWithRetryAfterHandling: surface the same "N attempt(s)"
            // shape as the RetryUtils exhaustion path instead of a plain status error.
            if (isRetryableStatus(e) && totalAttempts.get() >= MAX_ATTEMPTS) {
                int status = e.getResponse() != null ? e.getResponse().getStatus().getCode() : -1;
                throw new TypeSafeException(
                    status,
                    "TypeSafe API request failed after " + totalAttempts.get() + " attempt(s): " + e.getMessage()
                        + ". Fix: check the API status and rate limits, then retry the task with a lower concurrency.",
                    e
                );
            }
            throw mapStatusError(e);
        }

        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isBlank()) {
            throw new TypeSafeException(
                response.getStatus().getCode(),
                "TypeSafe API returned an empty response body (HTTP " + response.getStatus().getCode() + "). Fix: retry the task; if the problem persists, contact TypeSafe support."
            );
        }

        try {
            TypeSafeResponse typeSafeResponse = JacksonMapper.ofJson().readValue(responseBody, TypeSafeResponse.class);
            // Check for missing answers in successful response
            if (typeSafeResponse.getAnswers() == null || typeSafeResponse.getAnswers().isEmpty()) {
                throw new TypeSafeException(
                    response.getStatus().getCode(),
                    "TypeSafe API returned a successful response but with no answers. Fix: ensure the request includes at least one valid question."
                );
            }
            return typeSafeResponse;
        } catch (JsonProcessingException e) {
            throw new TypeSafeException(
                response.getStatus().getCode(),
                "TypeSafe API returned a malformed response body that could not be parsed as JSON (HTTP " + response.getStatus().getCode() + "). Fix: retry the task; if the problem persists, contact TypeSafe support.",
                e
            );
        }
    }

    /**
     * Executes the HTTP request, handling server-provided Retry-After headers by waiting
     * for the specified delay and retrying immediately (without additional exponential backoff).
     * For other retryable errors (network issues, 429/529 without Retry-After), the exception
     * is propagated to RetryUtils which applies exponential backoff.
     *
     * <p>All attempts share a single {@code MAX_ATTEMPTS} budget via {@code totalAttempts}:
     * each {@code client.request} call increments the counter, Retry-After waits never run
     * after the final attempt, and exceeding the budget propagates without further waiting.
     */
    private HttpResponse<String> executeRequestWithRetryAfterHandling(
        RunContext runContext,
        HttpClient client,
        HttpRequest request,
        AtomicInteger totalAttempts
    ) throws Exception {
        while (true) {
            checkKilled();
            int attempt = totalAttempts.incrementAndGet();
            try {
                HttpResponse<String> resp = client.request(request, String.class);
                if (resp.getStatus().getCode() >= 400) {
                    throw new HttpClientResponseException(
                        "Failed http request with response code '" + resp.getStatus().getCode() + "'",
                        resp
                    );
                }
                return resp;
            } catch (HttpClientResponseException e) {
                if (isRetryableStatus(e)) {
                    long waitMs = parseRetryAfterMs(e);
                    if (waitMs > 0) {
                        // Never wait after the final attempt: fail fast so callers see
                        // exactly MAX_ATTEMPTS requests with no trailing delay.
                        if (attempt >= MAX_ATTEMPTS) {
                            throw e;
                        }
                        // Server provided Retry-After: wait exactly that long, then retry immediately
                        // (no additional exponential backoff - the server-provided delay replaces it)
                        waitForRetryAfter(runContext.logger(), e);
                        continue; // Retry immediately after waiting
                    }
                }
                // For other retryable errors (network issues, 429/529 without Retry-After),
                // propagate to RetryUtils which will apply exponential backoff
                throw e;
            }
        }
    }

    /**
     * Determines if an error is retryable without considering server-provided Retry-After.
     * Used by RetryUtils to decide whether to apply exponential backoff.
     * Errors with server-provided Retry-After are handled separately in executeRequestWithRetryAfterHandling.
     */
    private boolean isRetryableWithoutRetryAfter(Throwable err) {
        if (err instanceof HttpClientResponseException responseException) {
            // Only retry 429/529 if they DON'T have a Retry-After header
            // (those are handled manually in executeRequestWithRetryAfterHandling)
            if (isRetryableStatus(responseException)) {
                long waitMs = parseRetryAfterMs(responseException);
                return waitMs <= 0; // Only retry via RetryUtils if NO Retry-After header
            }
            return false;
        }
        if (err instanceof HttpClientRequestException
            || err instanceof SocketException) {
            return true;
        }
        // Only wrap IOException causes that are connection-related
        if (err instanceof RuntimeException runtimeException && runtimeException.getCause() instanceof IOException) {
            IOException ioException = (IOException) runtimeException.getCause();
            // Only retry connection-related IOExceptions, not SSL or other issues
            return ioException instanceof SocketException
                || ioException instanceof java.net.ConnectException
                || ioException instanceof java.net.UnknownHostException
                || ioException instanceof java.net.NoRouteToHostException;
        }
        return false;
    }

    /**
     * Normalizes the base URL by stripping any trailing slash to avoid double slashes
     * when appending the API path.
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return baseUrl;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Serializes the request body, preserving explicit {@code null} map entries: a Choice option
     * with a {@code null} rubric means "no extra detail", which is different from a missing option.
     * (The default JSON mapper drops null map entries.)
     */
    private static String serializeRequestBody(Map<String, Object> body) {
        try {
            return REQUEST_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the TypeSafe request body.", e);
        }
    }

    /**
     * JSON mapper that omits null bean properties but keeps explicit {@code null} map entries,
     * mirroring {@code JacksonMapper.ofJsonWithNullValues()} from newer Kestra versions.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper REQUEST_MAPPER = JacksonMapper.ofJson()
        .copy()
        .setDefaultPropertyInclusion(
            com.fasterxml.jackson.annotation.JsonInclude.Value.construct(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS
            )
        );

    protected void emitUsageMetrics(RunContext runContext, TypeSafeResponse.Usage usage) {
        if (usage == null) {
            return;
        }
        runContext.metric(Counter.of("input.tokens", usage.getInputTokens()));
        runContext.metric(Counter.of("output.tokens", usage.getOutputTokens()));
    }

    /**
     * Exponential backoff: initial delay around 1 second, doubling per attempt up to an
     * 8-second cap, for an initial attempt plus 3 retries. A server-provided
     * {@code Retry-After} / {@code retry-after-ms} header overrides the calculated delay
     * for that attempt (see {@code waitForRetryAfter}).
     */
    static Exponential retryPolicy() {
        return Exponential.builder()
            .interval(Duration.ofSeconds(1))
            .delayFactor(2.0)
            .maxInterval(Duration.ofSeconds(8))
            .maxAttempts(MAX_ATTEMPTS)
            .build();
    }

    private static boolean isRetryable(Throwable err) {
        if (err instanceof HttpClientResponseException responseException) {
            return isRetryableStatus(responseException);
        }
        if (err instanceof HttpClientRequestException
            || err instanceof SocketException
            || err instanceof SSLException) {
            return true;
        }
        if (err instanceof RuntimeException runtimeException && runtimeException.getCause() instanceof IOException) {
            return true;
        }
        return false;
    }

    private static boolean isRetryableStatus(HttpClientResponseException e) {
        return e.getResponse() != null && RETRYABLE_STATUSES.contains(e.getResponse().getStatus().getCode());
    }

    /**
     * Honors the {@code retry-after} (seconds or HTTP-date) and {@code retry-after-ms}
     * (milliseconds) response headers with an interruptible, kill-aware wait.
     */
    private void waitForRetryAfter(org.slf4j.Logger logger, HttpClientResponseException e) throws InterruptedException {
        long waitMs = Math.min(parseRetryAfterMs(e), MAX_RETRY_AFTER_WAIT_MS);
        if (waitMs <= 0) {
            return;
        }
        logger.debug("TypeSafe API asked to wait {} ms before retrying.", waitMs);
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            if (isKilled()) {
                throw new KilledException("TypeSafe task was killed while waiting to retry.");
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            Thread.sleep(Math.min(remaining, RETRY_AFTER_POLL_MS));
        }
    }

    private static long parseRetryAfterMs(HttpClientResponseException e) {
        if (e.getResponse() == null || e.getResponse().getHeaders() == null) {
            return 0;
        }
        // Try standard header names (case-insensitive lookup via iteration for robustness)
        long waitMs = findRetryAfterHeader(e.getResponse().getHeaders());
        return Math.min(waitMs, MAX_RETRY_AFTER_WAIT_MS);
    }

    private static long findRetryAfterHeader(HttpHeaders headers) {
        if (headers == null) {
            return 0;
        }
        // Use the case-insensitive map view for robust header lookup
        Map<String, List<String>> headerMap = headers.map();
        
        // Try retry-after-ms first (milliseconds) - check multiple common casings
        List<String> retryAfterMsValues = headerMap.get("retry-after-ms");
        if (retryAfterMsValues == null || retryAfterMsValues.isEmpty()) {
            retryAfterMsValues = findHeaderCaseInsensitive(headerMap, "retry-after-ms");
        }
        if (retryAfterMsValues != null && !retryAfterMsValues.isEmpty()) {
            try {
                return Math.max(0, Long.parseLong(retryAfterMsValues.get(0).trim()));
            } catch (NumberFormatException ignored) {
                // fall through to retry-after
            }
        }
        // Try retry-after (seconds) - try multiple common casings
        List<String> retryAfterValues = headerMap.get("retry-after");
        if (retryAfterValues == null || retryAfterValues.isEmpty()) {
            retryAfterValues = headerMap.get("Retry-After");
        }
        if (retryAfterValues == null || retryAfterValues.isEmpty()) {
            retryAfterValues = headerMap.get("Retry-After-MS");
        }
        if (retryAfterValues == null || retryAfterValues.isEmpty()) {
            retryAfterValues = headerMap.get("Retry-After-Ms");
        }
        if (retryAfterValues == null || retryAfterValues.isEmpty()) {
            retryAfterValues = findHeaderCaseInsensitive(headerMap, "retry-after");
        }
        if (retryAfterValues == null || retryAfterValues.isEmpty()) {
            retryAfterValues = findHeaderCaseInsensitive(headerMap, "retry-after-ms");
        }
        if (retryAfterValues != null && !retryAfterValues.isEmpty()) {
            String value = retryAfterValues.get(0).trim();
            try {
                return Math.max(0, Long.parseLong(value) * 1000L);
            } catch (NumberFormatException ignored) {
                // fall through to HTTP-date parsing
            }
            try {
                Instant retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from);
                return Math.max(0, retryAt.toEpochMilli() - System.currentTimeMillis());
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static List<String> findHeaderCaseInsensitive(Map<String, List<String>> headerMap, String targetName) {
        String targetLower = targetName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : headerMap.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(targetLower)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private TypeSafeException exhausted(RetryUtils.RetryFailed e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        int status = -1;
        if (cause instanceof HttpClientResponseException responseException
            && responseException.getResponse() != null) {
            status = responseException.getResponse().getStatus().getCode();
        }
        return new TypeSafeException(
            status,
            "TypeSafe API request failed after " + e.getAttemptCount() + " attempt(s): " + cause.getMessage()
                + ". Fix: check the API status and rate limits, then retry the task with a lower concurrency.",
            cause
        );
    }

    private TypeSafeException mapStatusError(HttpClientResponseException e) {
        int status = e.getResponse() != null ? e.getResponse().getStatus().getCode() : -1;
        String detail = responseBodyExcerpt(e);
        return switch (status) {
            case 401 -> new TypeSafeException(status,
                "TypeSafe API authentication failed (HTTP 401): missing or invalid API key. Fix: check the 'apiKey' property, e.g. \"{{ secret('TYPESAFE_API_KEY') }}\".",
                e);
            case 422 -> new TypeSafeException(status,
                "TypeSafe API rejected the request (HTTP 422): the request body failed validation. Detail: " + detail
                    + " Fix: correct the offending field and retry.",
                e);
            default -> new TypeSafeException(status,
                "TypeSafe API request failed (HTTP " + status + "). Detail: " + detail
                    + " Fix: retry the task; for repeated 4xx errors, correct the request first.",
                e);
        };
    }

    private static String responseBodyExcerpt(HttpClientResponseException e) {
        if (e.getResponse() == null || e.getResponse().getBody() == null) {
            return e.getMessage();
        }
        Object body = e.getResponse().getBody();
        String text = body instanceof byte[] bytes ? new String(bytes) : String.valueOf(body);
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }

    /**
     * Renders the shared properties. The {@code questions} map entries are rendered with their
     * nested {@link Question} fields.
     */
    protected RenderedShared renderShared(RunContext runContext) throws IllegalVariableEvaluationException {
        String rApiKey = runContext.render(this.apiKey).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElseThrow();
        String rModel = runContext.render(this.model).as(String.class).orElseThrow();
        Map<String, Question> rQuestions = runContext.render(this.questions).asMap(String.class, Question.class);
        return new RenderedShared(rApiKey, rBaseUrl, rModel, rQuestions);
    }

    protected record RenderedShared(String apiKey, String baseUrl, String model, Map<String, Question> questions) {
    }
}
