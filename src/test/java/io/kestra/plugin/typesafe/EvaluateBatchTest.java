package io.kestra.plugin.typesafe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.SequenceWriter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.kestra.core.exceptions.KilledException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class EvaluateBatchTest {
    private static final String SUCCESS_BODY = """
        {
          "model": "jev-1.13.0",
          "answers": {
            "is_urgent": {"type": "noul", "noul": 0.95}
          },
          "usage": {"input_tokens": 10, "output_tokens": 5}
        }
        """;

    @Inject
    private RunContextFactory runContextFactory;

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void multipleRecords() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        RunContext runContext = runContextFactory.of();
        EvaluateBatch.Output output = runBatch(runContext, List.of("first", "second", "third"), 2);

        assertThat(output.getCount(), is(3L));
        assertThat(output.getModel(), is("jev-1.13.0"));

        List<Map<String, Object>> lines = readIonLines(runContext, output.getUri());
        assertThat(lines.size(), is(3));
        assertThat(lines.get(0).get("state"), is("first"));
        assertThat(lines.get(1).get("state"), is("second"));
        assertThat(lines.get(2).get("state"), is("third"));
        for (Map<String, Object> line : lines) {
            assertThat(line.keySet(), is(java.util.Set.of("state", "answers")));
            assertThat(asMap(asMap(line.get("answers")).get("is_urgent")).get("noul"), is(0.95));
        }
    }

    @Test
    void outOfOrderCompletionKeepsInputOrder() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(50)));
        server.stubFor(post(urlEqualTo("/v1/systemone")).withRequestBody(containing("slow-0"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(1500)));

        RunContext runContext = runContextFactory.of();
        EvaluateBatch.Output output = runBatch(runContext, List.of("slow-0", "fast-1", "fast-2"), 3);

        assertThat(output.getCount(), is(3L));
        List<Map<String, Object>> lines = readIonLines(runContext, output.getUri());
        assertThat(lines.get(0).get("state"), is("slow-0"));
        assertThat(lines.get(1).get("state"), is("fast-1"));
        assertThat(lines.get(2).get("state"), is("fast-2"));
    }

    @Test
    void boundedConcurrency() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(500)));

        RunContext runContext = runContextFactory.of();
        long start = System.currentTimeMillis();
        EvaluateBatch.Output output = runBatch(runContext, List.of("a", "b", "c", "d"), 2);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output.getCount(), is(4L));
        // 4 records x 500ms with concurrency 2 needs at least two waves; unbounded would take ~500ms.
        assertThat(elapsed >= 800, is(true));
        assertThat(elapsed < 10000, is(true));
    }

    @Test
    void concurrencyAboveOneOverlapsRequests() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(100)));
        server.stubFor(post(urlEqualTo("/v1/systemone")).withRequestBody(containing("slow"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(800)));

        RunContext runContext = runContextFactory.of();
        long start = System.currentTimeMillis();
        EvaluateBatch.Output output = runBatch(runContext, List.of("slow", "quick"), 2);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output.getCount(), is(2L));
        // Sequential execution would take ~900ms; overlapping takes ~800ms.
        assertThat(elapsed < 1500, is(true));
        List<Map<String, Object>> lines = readIonLines(runContext, output.getUri());
        assertThat(lines.get(0).get("state"), is("slow"));
        assertThat(lines.get(1).get("state"), is("quick"));
    }

    @Test
    void inlineStringStatesStayStrings() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        RunContext runContext = runContextFactory.of();
        EvaluateBatch.Output output = runBatch(runContext, List.of("state one", "state two", "state three"), 2);

        assertThat(output.getCount(), is(3L));
        for (String state : List.of("state one", "state two", "state three")) {
            server.verify(postRequestedFor(urlEqualTo("/v1/systemone")).withRequestBody(containing(state)));
        }

        List<Map<String, Object>> lines = readIonLines(runContext, output.getUri());
        assertThat(lines.size(), is(3));
        assertThat(lines.get(0).get("state"), is("state one"));
        assertThat(lines.get(1).get("state"), is("state two"));
        assertThat(lines.get(2).get("state"), is("state three"));
        for (Map<String, Object> line : lines) {
            assertThat(line.get("state") instanceof String, is(true));
        }
    }

    @Test
    void emptyBatch() throws Exception {
        RunContext runContext = runContextFactory.of();
        EvaluateBatch.Output output = runBatch(runContext, List.<String>of(), 2);

        assertThat(output.getCount(), is(0L));
        // Intentional: no API call is made for an empty dataset, so there is no model
        // that performed an evaluation; the output model is null rather than the
        // configured alias.
        assertThat(output.getModel(), org.hamcrest.Matchers.nullValue());
        assertThat(readIonLines(runContext, output.getUri()).isEmpty(), is(true));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void differingResponseModelsReportLastRecord() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));
        server.stubFor(post(urlEqualTo("/v1/systemone")).withRequestBody(containing("second"))
            .willReturn(aResponse().withStatus(200).withBody("""
                {
                  "model": "jev-9.9.9",
                  "answers": {"is_urgent": {"type": "noul", "noul": 0.1}},
                  "usage": {"input_tokens": 1, "output_tokens": 1}
                }
                """)));

        RunContext runContext = runContextFactory.of();
        EvaluateBatch.Output output = runBatch(runContext, List.of("first", "second"), 2);

        assertThat(output.getCount(), is(2L));
        assertThat(output.getModel(), is("jev-9.9.9"));
    }

    @Test
    void ionUriWithStringRecords() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        RunContext runContext = runContextFactory.of();
        File input = File.createTempFile("typesafe-input-strings", ".ion");
        try (SequenceWriter writer = JacksonMapper.ofIon().writerFor(String.class).writeValues(new FileOutputStream(input))) {
            writer.write("state one");
            writer.write("state two");
        }
        URI uri = runContext.storage().putFile(input);

        EvaluateBatch.Output output = batchTask(uri.toString(), 2).run(runContext);

        assertThat(output.getCount(), is(2L));
        assertThat(output.getModel(), is("jev-1.13.0"));
        server.verify(postRequestedFor(urlEqualTo("/v1/systemone")).withRequestBody(containing("\"state\":\"state one\"")));
        server.verify(postRequestedFor(urlEqualTo("/v1/systemone")).withRequestBody(containing("\"state\":\"state two\"")));

        List<Map<String, Object>> lines = readIonLines(runContext, output.getUri());
        assertThat(lines.size(), is(2));
        assertThat(lines.get(0).get("state"), is("state one"));
        assertThat(lines.get(1).get("state"), is("state two"));
        assertThat(lines.get(0).get("state") instanceof String, is(true));
    }

    @Test
    void fromInternalStorageUri() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        RunContext runContext = runContextFactory.of();
        File input = File.createTempFile("typesafe-input", ".ion");
        try (SequenceWriter writer = JacksonMapper.ofIon().writerFor(Map.class).writeValues(new FileOutputStream(input))) {
            writer.write(Map.of("ticket", "one"));
            writer.write(Map.of("ticket", "two"));
        }
        URI uri = runContext.storage().putFile(input);

        EvaluateBatch task = batchTask(uri.toString(), 2);
        EvaluateBatch.Output output = task.run(runContext);

        assertThat(output.getCount(), is(2L));
        List<Map<String, Object>> lines = readIonLines(runContext, output.getUri());
        assertThat(asMap(lines.get(0).get("state")).get("ticket"), is("one"));
        assertThat(asMap(lines.get(1).get("state")).get("ticket"), is("two"));
    }

    @Test
    void recordIndexedFailure() {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));
        server.stubFor(post(urlEqualTo("/v1/systemone")).withRequestBody(containing("bad-1"))
            .willReturn(aResponse().withStatus(422).withBody("{\"detail\": \"bad question\"}")));

        TypeSafeException e = assertThrows(
            TypeSafeException.class,
            () -> runBatch(runContextFactory.of(), List.of("ok-0", "bad-1", "ok-2"), 1)
        );

        assertThat(e.getStatusCode(), is(422));
        assertThat(e.getMessage(), containsString("Record index 1"));
        assertThat(e.getMessage(), containsString("422"));
    }

    @Test
    void batchRetryExhaustionIndexesRecord() {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(429).withBody("rate limited")));

        TypeSafeException e = assertThrows(
            TypeSafeException.class,
            () -> runBatch(runContextFactory.of(), List.of("only"), 1)
        );

        assertThat(e.getMessage(), containsString("Record index 0"));
        // 4 outer attempts; the underlying Apache HTTP client transparently retries each 429 once.
        server.verify(4 * 2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void concurrencyValidation() {
        assertThrows(
            IllegalArgumentException.class,
            () -> runBatch(runContextFactory.of(), List.of("a"), 0)
        );
        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class,
            () -> runBatch(runContextFactory.of(), List.of("a"), 21)
        );
        assertThat(e.getMessage(), containsString("'concurrency'"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void recordsAndTokenMetrics() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        RunContext runContext = runContextFactory.of();
        runBatch(runContext, List.of("a", "b"), 2);

        assertThat(metricValue(runContext, "records"), is(2.0));
        assertThat(metricValue(runContext, "input.tokens"), is(20.0));
        assertThat(metricValue(runContext, "output.tokens"), is(10.0));
    }

    @Test
    void killCancelsBatch() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(8000)));

        EvaluateBatch task = batchTask(List.of("a", "b", "c", "d", "e", "f"), 2);
        RunContext runContext = runContextFactory.of();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                task.run(runContext);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });

        waitForRequests(2, 15000);
        long killAt = System.currentTimeMillis();
        task.kill();
        // Simulate the worker, which interrupts the run thread after kill() returns.
        thread.interrupt();
        thread.join(15000);

        assertThat(thread.isAlive(), is(false));
        assertThat(thrown.get(), instanceOf(KilledException.class));
        assertThat(System.currentTimeMillis() - killAt < 14000, is(true));
    }

    @Test
    void killInterruptsRetryAfterWait() throws Exception {
        // 529 exercises the plugin's own interruptible Retry-After wait (Apache does not retry 529).
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(529).withHeader("Retry-After", "30").withBody("slow down")));

        EvaluateBatch task = batchTask(List.of("only"), 1);
        RunContext runContext = runContextFactory.of();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                task.run(runContext);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });

        waitForRequests(1, 15000);
        long killAt = System.currentTimeMillis();
        task.kill();
        // Simulate the worker, which interrupts the run thread after kill() returns.
        thread.interrupt();
        thread.join(15000);

        assertThat(thread.isAlive(), is(false));
        assertThat(thrown.get(), instanceOf(KilledException.class));
        // The 30s retry-after wait must not be observed; cancellation wins promptly.
        assertThat(System.currentTimeMillis() - killAt < 14000, is(true));
    }

    @Test
    void killStopsFurtherSubmissions() throws Exception {
        // Repeated to expose timing-sensitive races: each iteration must end KILLED with no
        // records submitted after the kill beyond the already in-flight window.
        for (int round = 0; round < 3; round++) {
            server.resetAll();
            server.stubFor(post(urlEqualTo("/v1/systemone"))
                .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(2000)));

            java.util.List<String> records = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                records.add("record-" + i);
            }

            EvaluateBatch task = batchTask(records, 2);
            RunContext runContext = runContextFactory.of();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    task.run(runContext);
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });

            waitForRequests(2, 15000);
            task.kill();
            // Snapshot immediately: the run thread may still be unwinding, so only the
            // already in-flight window (at most `concurrency` requests) may still arrive.
            int atKill = server.getAllServeEvents().size();
            // Simulate the worker, which interrupts the run thread after kill() returns.
            thread.interrupt();
            thread.join(15000);

            assertThat("round " + round + ": run thread stopped", thread.isAlive(), is(false));
            assertThat("round " + round + ": killed", thrown.get(), instanceOf(KilledException.class));
            // The bounded window (concurrency 2) stops the run far short of the dataset.
            assertThat("round " + round + ": stopped early", atKill < records.size(), is(true));
            assertThat(
                "round " + round + ": no submissions beyond the in-flight window after kill",
                server.getAllServeEvents().size() <= atKill + 2, is(true)
            );
        }
    }

    @Test
    void authorizationHeader() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        runBatch(runContextFactory.of(), List.of("a"), 1);

        server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
            .withHeader("Authorization", equalTo("Bearer test-key")));
    }

    @Test
    void nullFromIsRejected() {
        EvaluateBatch task = batchTask(null, 1);

        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class,
            () -> task.run(runContextFactory.of())
        );

        assertThat(e.getMessage(), containsString("'from'"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void boundedWindowLimitsInflightAndPreservesOrder() throws Exception {
        // WireMock request-journal concurrency proof: 12 records x 250ms with concurrency 2.
        // record-0 is slow (1000ms) so later records complete out of order; output must stay ordered.
        // Maximum overlap of [loggedDate, loggedDate + totalTime) must be at most 2.
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(250)));
        server.stubFor(post(urlEqualTo("/v1/systemone")).withRequestBody(containing("record-0"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(1000)));

        java.util.List<String> records = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            records.add("record-" + i);
        }

        RunContext runContext = runContextFactory.of();
        EvaluateBatch.Output output = runBatch(runContext, records, 2);

        assertThat(output.getCount(), is(12L));
        List<Map<String, Object>> lines = readIonLines(runContext, output.getUri());
        assertThat(lines.size(), is(12));
        for (int i = 0; i < 12; i++) {
            assertThat(lines.get(i).get("state"), is("record-" + i));
        }
        server.verify(12, postRequestedFor(urlEqualTo("/v1/systemone")));

        int maxOverlap = maxServeEventOverlap(server.getAllServeEvents());
        // Fails if more than two requests were simultaneously in flight.
        assertThat(maxOverlap <= 2, is(true));
        assertThat(maxOverlap >= 1, is(true));
    }

    @Test
    void earlyFailureStopsFurtherSubmission() {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));
        server.stubFor(post(urlEqualTo("/v1/systemone")).withRequestBody(containing("bad-1"))
            .willReturn(aResponse().withStatus(422).withBody("{\"detail\": \"bad question\"}")));

        java.util.List<String> records = new java.util.ArrayList<>();
        records.add("ok-0");
        records.add("bad-1");
        for (int i = 2; i < 10; i++) {
            records.add("ok-" + i);
        }

        TypeSafeException e = assertThrows(
            TypeSafeException.class,
            () -> runBatch(runContextFactory.of(), records, 1)
        );

        assertThat(e.getMessage(), containsString("Record index 1"));
        // Concurrency 1 is deterministic: only records 0 and 1 are attempted.
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    private EvaluateBatch batchTask(Object from, int concurrency) {
        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Does this convey urgency?").build()
        );
        return EvaluateBatch.builder()
            .id("batch")
            .type(EvaluateBatch.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .from(from)
            .concurrency(Property.ofValue(concurrency))
            .questions(Property.ofValue(questions))
            .build();
    }

    private EvaluateBatch.Output runBatch(RunContext runContext, List<String> records, int concurrency) throws Exception {
        return batchTask(records, concurrency).run(runContext);
    }

    private List<Map<String, Object>> readIonLines(RunContext runContext, URI uri) throws Exception {
        try (InputStream in = runContext.storage().getFile(uri)) {
            List<Map<String, Object>> lines = new java.util.ArrayList<>();
            for (Object line : FileSerde.readAll(in, Object.class).collectList().block()) {
                lines.add(asMap(line));
            }
            return lines;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Double metricValue(RunContext runContext, String name) {
        return runContext.metrics().stream()
            .filter(entry -> entry instanceof Counter && entry.getName().equals(name))
            .map(entry -> ((Counter) entry).getValue())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Metric '" + name + "' not found"));
    }

    private void waitForRequests(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (server.getAllServeEvents().size() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + expected + " HTTP requests");
            }
            Thread.sleep(100);
        }
    }

    private static int maxServeEventOverlap(java.util.List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events) {
        java.util.List<long[]> points = new java.util.ArrayList<>();
        for (com.github.tomakehurst.wiremock.stubbing.ServeEvent event : events) {
            if (!event.getRequest().getUrl().contains("/v1/systemone")) {
                continue;
            }
            long start = event.getRequest().getLoggedDate().getTime();
            Integer total = event.getTiming() != null ? event.getTiming().getTotalTime() : null;
            if (total == null && event.getTiming() != null) {
                total = event.getTiming().getAddedDelay();
            }
            long duration = total != null ? Math.max(0, total.longValue()) : 0L;
            points.add(new long[]{start, 1L});
            points.add(new long[]{start + duration, -1L});
        }
        points.sort((a, b) -> {
            int cmp = Long.compare(a[0], b[0]);
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(a[1], b[1]);
        });
        int current = 0;
        int max = 0;
        for (long[] point : points) {
            current += (int) point[1];
            max = Math.max(max, current);
        }
        return max;
    }
}
