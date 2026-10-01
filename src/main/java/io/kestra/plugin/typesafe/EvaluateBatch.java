package io.kestra.plugin.typesafe;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SequenceWriter;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.exceptions.KilledException;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Evaluate a dataset with TypeSafe",
    description = "Evaluates every record of a dataset against a map of typed questions via the TypeSafe System One API, with bounded concurrency, and writes one `{\"state\", \"answers\"}` line per record — in input order — to an ION file."
)
@Plugin(
    examples = {
        @Example(
            title = "Evaluate support tickets in batch",
            full = true,
            code = """
                id: typesafe_batch
                namespace: company.team

                tasks:
                  - id: fetch_tickets
                    type: io.kestra.plugin.core.http.Download
                    uri: "https://example.com/tickets.csv"
                  - id: convert_tickets
                    type: io.kestra.plugin.serdes.csv.CsvToIon
                    from: "{{ outputs.fetch_tickets.uri }}"
                  - id: evaluate_tickets
                    type: io.kestra.plugin.typesafe.EvaluateBatch
                    apiKey: "{{ secret('TYPESAFE_API_KEY') }}"
                    model: jev-latest
                    from: "{{ outputs.convert_tickets.uri }}"
                    concurrency: 5
                    questions:
                      is_urgent:
                        type: NOUL
                        instructions: Does this convey urgency?
                      department:
                        type: CHOICE
                        instructions: Which team should handle this?
                        options:
                          billing: Payments, invoicing, refunds
                          technical: Bugs, outages, integrations
                """
        )
    },
    metrics = {
        @Metric(
            name = "records",
            type = Counter.TYPE,
            unit = "count",
            description = "Number of records evaluated and written to the output file."
        ),
        @Metric(
            name = "input.tokens",
            type = Counter.TYPE,
            unit = "tokens",
            description = "Total number of input tokens reported by the TypeSafe API across all records."
        ),
        @Metric(
            name = "output.tokens",
            type = Counter.TYPE,
            unit = "tokens",
            description = "Total number of output tokens reported by the TypeSafe API across all records."
        )
    }
)
public class EvaluateBatch extends AbstractTypeSafe implements RunnableTask<EvaluateBatch.Output>, Data.From {
    private static final int DEFAULT_CONCURRENCY = 5;
    private static final int MAX_CONCURRENCY = 20;

    @Schema(
        title = Data.From.TITLE,
        description = Data.From.DESCRIPTION,
        anyOf = { String.class, List.class, Map.class }
    )
    @NotNull
    @PluginProperty(group = "source", internalStorageURI = true)
    private Object from;

    @Schema(
        title = "Concurrency",
        description = "Maximum number of records evaluated concurrently. Requests may complete out of order, but output lines always follow input order. "
            + "Note that a persistently rate-limited (HTTP 429) call can issue up to 8 HTTP requests per record "
            + "(initial attempt plus 3 retries, each transparently retried once by the underlying HTTP client), "
            + "so keep this value modest to stay within the API rate limits."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<@Min(1) @Max(20) Integer> concurrency = Property.ofValue(DEFAULT_CONCURRENCY);

    @Override
    public Output run(RunContext runContext) throws Exception {
        RenderedShared shared = renderShared(runContext);
        int rConcurrency = runContext.render(this.concurrency).as(Integer.class).orElse(DEFAULT_CONCURRENCY);
        if (rConcurrency < 1 || rConcurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException(
                "Invalid 'concurrency': " + rConcurrency + ". Fix: set 'concurrency' to a value between 1 and " + MAX_CONCURRENCY + "."
            );
        }
        if (from == null) {
            throw new IllegalArgumentException(
                "Invalid 'from': the batch input is missing. Fix: set 'from' to a list of records or an internal storage URI."
            );
        }
        validateQuestions(shared.questions());

        ExecutorService current = Executors.newFixedThreadPool(
            rConcurrency,
            Thread.ofPlatform().name("typesafe-evaluate-", 0).daemon(true).factory()
        );
        HttpClient client = newClient(runContext);
        try {
            return runBounded(runContext, current, client, shared, rConcurrency);
        } finally {
            shutdown(current);
            releaseClient();
        }
    }

    /**
     * Submits records and writes results together through a genuinely bounded window.
     *
     * <p>At most {@code concurrency} futures are outstanding at any time. Results are
     * written in original input order (blocking on the earliest pending future, even if
     * later futures complete first). Each written future is removed and its references
     * released immediately. A failure stops further submission: later pending futures
     * are cancelled and the indexed error propagates.
     */
    private Output runBounded(
        RunContext runContext,
        ExecutorService current,
        HttpClient client,
        RenderedShared shared,
        int concurrency
    ) throws Exception {
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        long count = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        String model = null;

        Map<Integer, Future<RecordResult>> pending = new LinkedHashMap<>();
        List<Future<RecordResult>> killable = new CopyOnWriteArrayList<>();
        Iterator<Object> iterator;
        try {
            iterator = readRecords(runContext).toIterable().iterator();
        } catch (RuntimeException e) {
            if (isKilled()) {
                throw new KilledException("TypeSafe batch evaluation was killed while reading input records.");
            }
            throw e;
        }
        int nextIndex = 0;
        int nextToWrite = 0;

        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE);
             SequenceWriter writer = JacksonMapper.ofIon().writerFor(Map.class).writeValues(out)) {
            while (true) {
                checkKilled();
                // Fill the window up to the concurrency bound; stop filling on failure
                // (failures throw below, so this loop simply never resumes after one).
                while (pending.size() < concurrency) {
                    checkKilled();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    Object record;
                    try {
                        record = iterator.next();
                    } catch (RuntimeException e) {
                        if (isKilled()) {
                            throw new KilledException("TypeSafe batch evaluation was killed while reading input records.");
                        }
                        throw e;
                    }
                    final int recordIndex = nextIndex++;
                    final Object state = record;
                    Future<RecordResult> future = current.submit(() -> evaluateRecord(runContext, client, shared, recordIndex, state));
                    pending.put(recordIndex, future);
                    killable.add(future);
                }
                if (pending.isEmpty()) {
                    break;
                }
                Future<RecordResult> earliest = pending.get(nextToWrite);
                if (earliest == null) {
                    throw new IllegalStateException("TypeSafe batch evaluation lost track of record index " + nextToWrite + ".");
                }
                RecordResult result;
                try {
                    result = takeInOrder(nextToWrite, earliest);
                } catch (Exception e) {
                    cancelPendingAfter(current, pending, killable, nextToWrite);
                    throw e;
                }
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("state", result.state());
                line.put("answers", result.answers());
                writer.write(line);
                count++;
                inputTokens += result.inputTokens();
                outputTokens += result.outputTokens();
                if (result.model() != null) {
                    model = result.model();
                }
                // Release references as soon as the result is written.
                pending.remove(nextToWrite);
                killable.remove(earliest);
                nextToWrite++;
            }
        }

        URI uri = runContext.storage().putFile(tempFile);
        runContext.metric(Counter.of("records", count));
        runContext.metric(Counter.of("input.tokens", inputTokens));
        runContext.metric(Counter.of("output.tokens", outputTokens));
        return Output.builder()
            .uri(uri)
            .count(count)
            .model(model)
            .build();
    }

    /**
     * Reads input records without hand-rolling URI or ION parsing.
     *
     * <p>Inline lists and maps are consumed directly (each element is one record, in order) so
     * records keep their natural JSON shape ({@code string}, {@code object} or {@code array})
     * for the {@code state} field. Strings (URIs such as {@code kestra://...} or JSON documents)
     * are resolved through {@link Data#from(Object)}, which owns URI detection and ION parsing.
     * They are read as {@link JsonNode}s (rather than maps) so ION/JSON records keep their
     * natural shape too — including plain-string records.
     */
    @SuppressWarnings("unchecked")
    private Flux<Object> readRecords(RunContext runContext) throws IllegalVariableEvaluationException, JsonProcessingException {
        if (from instanceof List<?> list) {
            return Flux.fromIterable(list).map(throwFunction(item -> renderRecord(runContext, item)));
        }
        if (from instanceof Map<?, ?> map) {
            return Flux.just(renderRecord(runContext, map));
        }
        return Data.from(from)
            .readAs(runContext, JsonNode.class, map -> JacksonMapper.ofJson().valueToTree(map))
            .map(throwFunction(node -> jsonNodeToJava(node)));
    }

    private static Object jsonNodeToJava(JsonNode node) throws JsonProcessingException {
        return JacksonMapper.ofJson().treeToValue(node, Object.class);
    }

    @SuppressWarnings("unchecked")
    private static Object renderRecord(RunContext runContext, Object record) throws IllegalVariableEvaluationException {
        if (record instanceof String str) {
            return runContext.render(str);
        }
        if (record instanceof Map<?, ?> map) {
            return runContext.render((Map<String, Object>) map);
        }
        if (record instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderRecord(runContext, item));
            }
            return rendered;
        }
        return record;
    }

    private RecordResult evaluateRecord(
        RunContext runContext,
        HttpClient client,
        RenderedShared shared,
        int recordIndex,
        Object state
    ) throws Exception {
        checkKilled();
        try {
            TypeSafeResponse response = evaluateState(
                runContext, client,
                shared.baseUrl(), shared.apiKey(), shared.model(),
                state, shared.questions()
            );
            long inputTokens = response.getUsage() != null ? response.getUsage().getInputTokens() : 0L;
            long outputTokens = response.getUsage() != null ? response.getUsage().getOutputTokens() : 0L;
            return new RecordResult(state, response.getAnswers(), response.getModel(), inputTokens, outputTokens);
        } catch (TypeSafeException e) {
            throw new TypeSafeException(
                e.getStatusCode(),
                "Record index " + recordIndex + ": " + e.getMessage(),
                e.getCause() != null ? e.getCause() : e
            );
        }
    }

    /**
     * Blocks for the next in-order record, preserving indexed failures and kill semantics.
     * Later pending futures may already be complete; they stay queued until their turn.
     */
    private RecordResult takeInOrder(int index, Future<RecordResult> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (isKilled()) {
                throw new KilledException("TypeSafe batch evaluation was killed.");
            }
            throw e;
        } catch (CancellationException e) {
            if (isKilled()) {
                throw new KilledException("TypeSafe batch evaluation was killed.");
            }
            throw new TypeSafeException("Record index " + index + ": the evaluation was cancelled before completing.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof KilledException killedException) {
                throw killedException;
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                if (isKilled()) {
                    throw new KilledException("TypeSafe batch evaluation was killed.");
                }
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new TypeSafeException("Record index " + index + ": " + cause.getMessage(), cause);
        }
    }

    private void cancelPendingAfter(
        ExecutorService current,
        Map<Integer, Future<RecordResult>> pending,
        List<Future<RecordResult>> killable,
        int failedIndex
    ) {
        for (Map.Entry<Integer, Future<RecordResult>> entry : pending.entrySet()) {
            if (entry.getKey() > failedIndex) {
                entry.getValue().cancel(true);
                killable.remove(entry.getValue());
            }
        }
        current.shutdownNow();
    }

    private void shutdown(ExecutorService current) {
        current.shutdownNow();
        try {
            current.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record RecordResult(Object state, Map<String, Answer> answers, String model, long inputTokens, long outputTokens) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Output URI",
            description = "URI of the ION file in Kestra's internal storage, with one `{\"state\", \"answers\"}` line per input record, in input order."
        )
        private final URI uri;

        @Schema(
            title = "Record count",
            description = "Number of records evaluated and written to the output file."
        )
        private final long count;

        @Schema(
            title = "Model",
            description = "The versioned model id reported by the TypeSafe API responses. When responses disagree "
                + "(e.g. an alias rolled over mid-batch), this is the last evaluated record's model; per-record "
                + "answers always correspond to the model that produced them. Null when the dataset is empty and no evaluation was performed."
        )
        private final String model;
    }
}
