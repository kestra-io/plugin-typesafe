package io.kestra.plugin.typesafe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class EvaluateTest {
    private static final String API_KEY = "test-key";

    private static final String SUCCESS_BODY = """
        {
          "model": "jev-1.13.0",
          "answers": {
            "is_urgent": {"type": "noul", "noul": 0.95},
            "department": {
              "type": "choice",
              "choice": "billing",
              "probabilities": {"billing": 0.88, "technical": 0.12},
              "confidence": 0.81
            },
            "frustration": {
              "type": "score",
              "score": 1.05,
              "legend": {"0": "Calm", "1": "Frustrated", "2": "Very angry"},
              "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05},
              "confidence": 0.92
            }
          },
          "usage": {"input_tokens": 296, "output_tokens": 20}
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
    void successfulRequest() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        Evaluate.Output output = runDefaultTask(runContextFactory.of());

        assertThat(output.getModel(), is("jev-1.13.0"));
        assertThat(output.getAnswers().size(), is(3));

        Answer noul = output.getAnswers().get("is_urgent");
        assertThat(noul.getType(), is(QuestionType.NOUL));
        assertThat(noul.getNoul(), is(0.95));

        Answer choice = output.getAnswers().get("department");
        assertThat(choice.getType(), is(QuestionType.CHOICE));
        assertThat(choice.getChoice(), is("billing"));
        assertThat(choice.getProbabilities().get("billing"), is(0.88));
        assertThat(choice.getConfidence(), is(0.81));

        Answer score = output.getAnswers().get("frustration");
        assertThat(score.getType(), is(QuestionType.SCORE));
        assertThat(score.getScore(), is(1.05));
        assertThat(score.getLegend().get("1"), is("Frustrated"));
        assertThat(score.getConfidence(), is(0.92));
    }

    @Test
    void requestSerialization() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        runDefaultTask(runContextFactory.of());

        server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
            .withRequestBody(equalToJson("""
                {
                  "state": "Help! My payouts have been failing for 3 days.",
                  "model": "jev-latest",
                  "questions": {
                    "is_urgent": {
                      "type": "noul",
                      "instructions": "Does this convey urgency?",
                      "criteria": {"true": "Explicitly time-sensitive", "false": "No urgency expressed"}
                    },
                    "department": {
                      "type": "choice",
                      "instructions": "Which team should handle this?",
                      "criteria": {"billing": "Payments, invoicing, refunds", "technical": null}
                    },
                    "frustration": {
                      "type": "score",
                      "instructions": "How frustrated is the customer?",
                      "criteria": ["Calm", "Frustrated", "Very angry"]
                    }
                  }
                }
                """)));
    }

    @Test
    void authorizationHeader() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        runDefaultTask(runContextFactory.of());

        server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
            .withHeader("Authorization", equalTo("Bearer " + API_KEY)));
    }

    @Test
    void unauthorized() {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(401).withBody("{\"detail\": \"Invalid API key\"}")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(401));
        assertThat(e.getMessage(), containsString("401"));
        assertThat(e.getMessage(), containsString("apiKey"));
        server.verify(1, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void validationError() {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(422).withBody("{\"detail\": [{\"loc\": [\"questions\", \"department\"], \"msg\": \"missing options\"}]}")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(422));
        assertThat(e.getMessage(), containsString("422"));
        assertThat(e.getMessage(), containsString("department"));
        server.verify(1, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void serverErrorIsNotRetried() {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(500).withBody("boom")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(500));
        server.verify(1, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void rateLimitedThenSuccess() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("rate-limit")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withBody("rate limited"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("rate-limit")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        Evaluate.Output output = runDefaultTask(runContextFactory.of());

        assertThat(output.getModel(), is("jev-1.13.0"));
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void retriedAttemptsDoNotDoubleCountTokens() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("no-double-count")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withBody("rate limited"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("no-double-count")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        RunContext runContext = runContextFactory.of();
        runDefaultTask(runContext);

        // Usage is emitted once for the successful response only, not per attempt.
        assertThat(metricValue(runContext, "input.tokens"), is(296.0));
        assertThat(metricValue(runContext, "output.tokens"), is(20.0));
    }

    @Test
    void overloaded529ThenSuccess() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("overload")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(529).withBody("overloaded"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("overload")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        Evaluate.Output output = runDefaultTask(runContextFactory.of());

        assertThat(output.getModel(), is("jev-1.13.0"));
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void exhaustedRetries() {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(429).withBody("rate limited")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(429));
        assertThat(e.getMessage(), containsString("4 attempt(s)"));
        // 4 outer attempts; the underlying Apache HTTP client transparently retries each 429 once,
        // so 8 HTTP requests are issued in total.
        server.verify(4 * 2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void retryAfterMsHeaderIsHonored_529() throws Exception {
        // Use 529 status to test plugin-layer retry-after (Apache does NOT retry 529)
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after-ms-529")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(529).withHeader("retry-after-ms", "100").withBody("slow down"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after-ms-529")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        long start = System.currentTimeMillis();
        Evaluate.Output output = runDefaultTask(runContextFactory.of());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output.getModel(), is("jev-1.13.0"));
        // Server said retry-after-ms: 100ms. The plugin should wait ~100ms and retry
        // immediately WITHOUT additional exponential backoff.
        assertThat(elapsed >= 80, is(true));
        assertThat(elapsed < 800, is(true)); // Should NOT be ~1100ms+ (100ms server + 1s exponential)
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void retryAfterHeaderIsHonored_529() throws Exception {
        // Use 529 status to test plugin-layer retry-after (Apache does NOT retry 529)
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after-529")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(529).withHeader("Retry-After", "2").withBody("slow down"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after-529")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        long start = System.currentTimeMillis();
        Evaluate.Output output = runDefaultTask(runContextFactory.of());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output.getModel(), is("jev-1.13.0"));
        // Server said Retry-After: 2 seconds. The plugin should wait ~2 seconds and retry
        // immediately WITHOUT additional exponential backoff.
        assertThat(elapsed >= 1900, is(true));
        assertThat(elapsed < 2900, is(true)); // Should NOT be ~4000ms (2s server + 2s exponential)
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void exhausted529Retries_NoTrailingWait() {
        // 529 with NO Retry-After header: all 4 outer attempts run (4 requests, no trailing wait after last)
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(529).withBody("overloaded")));

        long start = System.currentTimeMillis();
        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(e.getStatusCode(), is(529));
        assertThat(e.getMessage(), containsString("4 attempt(s)"));
        // 4 outer attempts; no Apache retries for 529, so 4 requests total
        // Should fail quickly without trailing wait after the 4th attempt
        server.verify(4, postRequestedFor(urlEqualTo("/v1/systemone")));
        // If there were an extra trailing wait, elapsed would be significantly larger
        assertThat(elapsed < 8000, is(true)); // 4 attempts with max 1s+2s+4s+8s backoff < 8s
    }

    @Test
    void retryAfterHeaderIsHonored_429() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody("slow down"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        long start = System.currentTimeMillis();
        Evaluate.Output output = runDefaultTask(runContextFactory.of());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output.getModel(), is("jev-1.13.0"));
        // Server said Retry-After: 1 second. The plugin should wait ~1 second and retry
        // immediately WITHOUT additional exponential backoff.
        // If it incorrectly added exponential backoff (1s initial), elapsed would be ~2000ms.
        assertThat(elapsed >= 900, is(true));
        assertThat(elapsed < 1800, is(true)); // Should NOT be ~2000ms (1s server + 1s exponential)
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void serviceUnavailable503IsNotRetriedByPlugin() {
        // 503 is retried once transparently by the underlying Apache HTTP client (like 429),
        // but this plugin does not retry it further: 2 requests, then a fast failure.
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(503).withBody("unavailable")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(503));
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void exhausted529Retries() {
        // 529 is not covered by the Apache retry layer, so all 4 outer attempts run: 4 requests.
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(529).withBody("overloaded")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(529));
        assertThat(e.getMessage(), containsString("4 attempt(s)"));
        server.verify(4, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void pluginLayerRetryAfterIsHonored() throws Exception {
        // The first 429 is consumed by the underlying Apache client's transparent retry;
        // the second 429 reaches this plugin's retry handling, which must honor Retry-After too.
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("plugin-retry-after")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody("slow down"))
            .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("plugin-retry-after")
            .whenScenarioStateIs("second")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody("slow down"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("plugin-retry-after")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        long start = System.currentTimeMillis();
        Evaluate.Output output = runDefaultTask(runContextFactory.of());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output.getModel(), is("jev-1.13.0"));
        // Two Retry-After: 1 second each. Total should be ~2000ms (2 x 1s server delays)
        // NOT ~4000ms (2s server + 2s exponential backoff)
        assertThat(elapsed >= 1800, is(true));
        assertThat(elapsed < 3000, is(true)); // Should NOT be ~4000ms (2s server + 2s exponential)
        server.verify(3, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void retryAfterMsHeaderIsHonored() throws Exception {
        // Use 429 status (same as retryAfterHeaderIsHonored) to isolate header parsing
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after-ms")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("retry-after-ms", "500").withBody("slow down"))
            .willSetStateTo("recovered"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry-after-ms")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        long start = System.currentTimeMillis();
        Evaluate.Output output = runDefaultTask(runContextFactory.of());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(output.getModel(), is("jev-1.13.0"));
        // Server said retry-after-ms: 500ms. The plugin should wait ~500ms and retry
        // immediately WITHOUT additional exponential backoff.
        assertThat(elapsed >= 400, is(true));
        // More generous upper bound to account for test environment variability
        assertThat(elapsed < 2500, is(true)); // Should NOT be ~2500ms+ (500ms server + exponential backoff)
        server.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void emptyResponseBody() {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody("")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("empty response body"));
    }

    @Test
    void missingAnswersInResponse() {
        // 200 response with no 'answers' field
        String responseNoAnswers = """
            {
              "model": "jev-1.13.0",
              "usage": {"input_tokens": 100, "output_tokens": 10}
            }
            """;
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(responseNoAnswers)));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(200));
        assertThat(e.getMessage(), containsString("successful response but with no answers"));
        assertThat(e.getMessage(), containsString("at least one valid question"));
        server.verify(1, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void malformedResponseBody() {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody("this is not json")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("malformed"));
    }

    @Test
    void tokenMetrics() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        RunContext runContext = runContextFactory.of();
        runDefaultTask(runContext);

        assertThat(metricValue(runContext, "input.tokens"), is(296.0));
        assertThat(metricValue(runContext, "output.tokens"), is(20.0));
    }

    @Test
    void validationErrorsFailBeforeHttp() {
        Map<String, Question> questions = Map.of(
            "department", Question.builder().type(QuestionType.CHOICE).instructions("Pick one.").build()
        );
        Evaluate task = Evaluate.builder()
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue("some state"))
            .questions(Property.ofValue(questions))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("'department'"));
        assertThat(e.getMessage(), containsString("'options'"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void propertiesRenderExpressions() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        // The task is deserialized from YAML exactly as Kestra parses flows, so properties
        // follow the production rendering path (unlike Property.ofValue, which skips rendering).
        String yaml = """
            id: evaluate
            type: io.kestra.plugin.typesafe.Evaluate
            apiKey: test-key
            baseUrl: "%s"
            model: jev-latest
            state: "{{ ticket }}"
            questions:
              is_urgent:
                type: noul
                instructions: "Is {{ topic }} urgent?"
            """.formatted(server.baseUrl());
        Evaluate task = io.kestra.core.serializers.JacksonMapper.ofYaml().readValue(yaml, Evaluate.class);

        RunContext runContext = runContextFactory.of(Map.of("topic", "payouts", "ticket", "Payouts failing"));
        task.run(runContext);

        server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
            .withRequestBody(equalToJson("""
                {
                  "state": "Payouts failing",
                  "model": "jev-latest",
                  "questions": {
                    "is_urgent": {"type": "noul", "instructions": "Is payouts urgent?"}
                  }
                }
                """)));
    }

    @Test
    void questionsFromUpstreamOutputRenderCorrectly() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        // Simulates `questions: "{{ outputs.producer.values }}"`: the whole questions map
        // arrives as a rendered expression, not as directly constructed Java objects.
        Map<String, Object> upstreamQuestions = Map.of(
            "is_urgent", Map.of("type", "noul", "instructions", "Does this convey urgency?")
        );
        String yaml = """
            id: evaluate
            type: io.kestra.plugin.typesafe.Evaluate
            apiKey: test-key
            baseUrl: "%s"
            model: jev-latest
            state: Help! My payouts have been failing for 3 days.
            questions: "{{ upstreamQuestions }}"
            """.formatted(server.baseUrl());
        Evaluate task = io.kestra.core.serializers.JacksonMapper.ofYaml().readValue(yaml, Evaluate.class);

        RunContext runContext = runContextFactory.of(Map.of("upstreamQuestions", upstreamQuestions));
        Evaluate.Output output = task.run(runContext);

        assertThat(output.getModel(), is("jev-1.13.0"));
        assertThat(output.getAnswers().size(), is(3));
        server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
            .withRequestBody(equalToJson("""
                {
                  "state": "Help! My payouts have been failing for 3 days.",
                  "model": "jev-latest",
                  "questions": {
                    "is_urgent": {"type": "noul", "instructions": "Does this convey urgency?"}
                  }
                }
                """)));
    }

    @Test
    void objectValuedState() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("customer", "John Doe");
        state.put("issue", "Payment failed");
        state.put("metadata", Map.of("orderId", 123, "retryCount", 3));

        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue(state))
            .questions(Property.ofValue(Map.of(
                "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
            )))
            .build();

        RunContext runContext = runContextFactory.of();
        Evaluate.Output output = task.run(runContext);

        assertThat(output.getModel(), is("jev-1.13.0"));

        server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
            .withRequestBody(equalToJson("""
                {
                  "state": {
                    "customer": "John Doe",
                    "issue": "Payment failed",
                    "metadata": {"orderId": 123, "retryCount": 3}
                  },
                  "model": "jev-latest",
                  "questions": {
                    "is_urgent": {"type": "noul", "instructions": "Is this urgent?"}
                  }
                }
                """)));
    }

    @Test
    void always529WithRetryAfterIsBoundedToFourAttempts() {
        // 529 uses the plugin-layer Retry-After path (no Apache transparent retry):
        // an always-529 with Retry-After must stop after exactly four total attempts.
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(529).withHeader("retry-after-ms", "50").withBody("overloaded")));

        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));

        assertThat(e.getStatusCode(), is(529));
        assertThat(e.getMessage(), containsString("4 attempt(s)"));
        server.verify(4, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void retryAfterOnFinalAttemptDoesNotWait() {
        // First three attempts wait 50ms each; the fourth fails with Retry-After: 2.
        // A correct implementation throws immediately without the trailing 2s wait.
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("final-retry-after")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(529).withHeader("retry-after-ms", "50").withBody("overloaded"))
            .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("final-retry-after")
            .whenScenarioStateIs("second")
            .willReturn(aResponse().withStatus(529).withHeader("retry-after-ms", "50").withBody("overloaded"))
            .willSetStateTo("third"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("final-retry-after")
            .whenScenarioStateIs("third")
            .willReturn(aResponse().withStatus(529).withHeader("retry-after-ms", "50").withBody("overloaded"))
            .willSetStateTo("fourth"));
        server.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("final-retry-after")
            .whenScenarioStateIs("fourth")
            .willReturn(aResponse().withStatus(529).withHeader("Retry-After", "2").withBody("overloaded")));

        long start = System.currentTimeMillis();
        TypeSafeException e = assertThrows(TypeSafeException.class, () -> runDefaultTask(runContextFactory.of()));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(e.getStatusCode(), is(529));
        assertThat(e.getMessage(), containsString("4 attempt(s)"));
        server.verify(4, postRequestedFor(urlEqualTo("/v1/systemone")));
        // Three 50ms waits ≈ 150ms; a trailing 2s wait would push elapsed past 2000ms.
        assertThat(elapsed < 1500, is(true));
    }

    @Test
    void trailingSlashBaseUrlHasSingleSlash() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY)));

        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl() + "/"))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue("hello"))
            .questions(Property.ofValue(questions))
            .build();

        Evaluate.Output output = task.run(runContextFactory.of());

        assertThat(output.getModel(), is("jev-1.13.0"));
        // urlEqualTo("/v1/systemone") fails on a double slash, so this proves no "//v1/systemone".
        server.verify(1, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void emptyStateIsRejectedWithGuidance() {
        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue(""))
            .questions(Property.ofValue(questions))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("'state'"));
        assertThat(e.getMessage(), containsString("string, object or array"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void nullStateIsRejectedWithGuidance() {
        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(null)
            .questions(Property.ofValue(questions))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("'state'"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void emptyApiKeyIsRejectedWithGuidance() {
        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(""))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue("hello"))
            .questions(Property.ofValue(questions))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("'apiKey'"));
        assertThat(e.getMessage(), containsString("secret('TYPESAFE_API_KEY')"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void emptyBaseUrlIsRejectedWithGuidance() {
        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue("  "))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue("hello"))
            .questions(Property.ofValue(questions))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("'baseUrl'"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void emptyModelIsRejectedWithGuidance() {
        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue(""))
            .state(Property.ofValue("hello"))
            .questions(Property.ofValue(questions))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getMessage(), containsString("'model'"));
        server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void omittedOptionalPropertiesUseDefaults() throws Exception {
        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(null)
            .model(null)
            .state(Property.ofValue("hello"))
            .questions(Property.ofValue(questions))
            .build();

        RunContext runContext = runContextFactory.of();
        AbstractTypeSafe.RenderedShared shared = task.renderShared(runContext);

        assertThat(shared.baseUrl(), is("https://api.typesafe.ai"));
        assertThat(shared.model(), is("jev-latest"));
        assertThat(shared.apiKey(), is(API_KEY));
    }

    @Test
    void killDuringRequestSurfacesKilled() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(SUCCESS_BODY).withFixedDelay(8000)));

        Map<String, Question> questions = Map.of(
            "is_urgent", Question.builder().type(QuestionType.NOUL).instructions("Is this urgent?").build()
        );
        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue("hello"))
            .questions(Property.ofValue(questions))
            .build();

        RunContext runContext = runContextFactory.of();
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                task.run(runContext);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });

        long deadline = System.currentTimeMillis() + 15000;
        while (server.getAllServeEvents().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        task.kill();
        // Simulate the worker, which interrupts the run thread after kill() returns.
        thread.interrupt();
        thread.join(15000);

        assertThat(thread.isAlive(), is(false));
        assertThat(thrown.get() instanceof io.kestra.core.exceptions.KilledException, is(true));
    }

    private Evaluate.Output runDefaultTask(RunContext runContext) throws Exception {
        Map<String, Object> billing = Map.of("billing", "Payments, invoicing, refunds");
        Map<String, Object> options = new LinkedHashMap<>(billing);
        options.put("technical", null);
        Map<String, Question> questions = new LinkedHashMap<>();
        questions.put("is_urgent", Question.builder()
            .type(QuestionType.NOUL)
            .instructions("Does this convey urgency?")
            .whenTrue("Explicitly time-sensitive")
            .whenFalse("No urgency expressed")
            .build());
        questions.put("department", Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Which team should handle this?")
            .options(options)
            .build());
        questions.put("frustration", Question.builder()
            .type(QuestionType.SCORE)
            .instructions("How frustrated is the customer?")
            .levels(List.of("Calm", "Frustrated", "Very angry"))
            .build());

        Evaluate task = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue("Help! My payouts have been failing for 3 days."))
            .questions(Property.ofValue(questions))
            .build();
        return task.run(runContext);
    }

    private static Double metricValue(RunContext runContext, String name) {
        return runContext.metrics().stream()
            .filter(entry -> entry instanceof Counter && entry.getName().equals(name))
            .map(entry -> ((Counter) entry).getValue())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Metric '" + name + "' not found"));
    }
}
