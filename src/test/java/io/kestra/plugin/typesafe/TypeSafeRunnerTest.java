package io.kestra.plugin.typesafe;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.junit.annotations.LoadFlows;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Executes plugin tasks through the real in-memory runner, verifying task registration,
 * flow-level YAML configuration and execution outputs.
 *
 * <p>Flows live in {@code src/test/resources/flows} and are loaded/executed through Kestra's
 * {@code @LoadFlows} test utility, so the YAML is deserialized by Kestra (not merely parsed)
 * and run end-to-end. The WireMock base URL is passed as a flow input.
 */
@KestraTest(startRunner = true)
class TypeSafeRunnerTest {
    private static final String RESPONSE_BODY = """
        {
          "model": "jev-1.13.0",
          "answers": {
            "is_urgent": {"type": "noul", "noul": 0.95}
          },
          "usage": {"input_tokens": 100, "output_tokens": 10}
        }
        """;

    @Inject
    private TestRunnerUtils runnerUtils;

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(RESPONSE_BODY)));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @LoadFlows("flows/typesafe-evaluate.yaml")
    void evaluateFlow() throws Exception {
        Execution execution = runnerUtils.runOne(
            "main", "io.kestra.tests", "typesafe-evaluate", null,
            (f, e) -> Map.of("baseUrl", server.baseUrl())
        );

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
        assertThat(execution.getTaskRunList().size(), is(1));

        Map<String, Object> outputs = execution.getTaskRunList().getFirst().getOutputs();
        assertThat(outputs.get("model"), is("jev-1.13.0"));
        Map<String, Object> answers = asMap(outputs.get("answers"));
        assertThat(asMap(answers.get("is_urgent")).get("noul"), is(0.95));
    }

    @Test
    @LoadFlows("flows/typesafe-upstream.yaml")
    void evaluateWithUpstreamQuestions() throws Exception {
        Execution execution = runnerUtils.runOne(
            "main", "io.kestra.tests", "typesafe-upstream", null,
            (f, e) -> Map.of("baseUrl", server.baseUrl())
        );

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));

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

        Map<String, Object> outputs = execution.getTaskRunList().stream()
            .filter(taskRun -> taskRun.getTaskId().equals("evaluate"))
            .findFirst()
            .orElseThrow()
            .getOutputs();
        assertThat(outputs.get("model"), is("jev-1.13.0"));
        Map<String, Object> answers = asMap(outputs.get("answers"));
        assertThat(asMap(answers.get("is_urgent")).get("noul"), is(0.95));
    }

    @Test
    @LoadFlows("flows/typesafe-batch.yaml")
    void evaluateBatchFlow() throws Exception {
        Execution execution = runnerUtils.runOne(
            "main", "io.kestra.tests", "typesafe-batch", null,
            (f, e) -> Map.of("baseUrl", server.baseUrl())
        );

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));

        Map<String, Object> outputs = execution.getTaskRunList().getFirst().getOutputs();
        assertThat(outputs.get("count"), is(2));
        assertThat(outputs.get("model"), is("jev-1.13.0"));
        assertThat(outputs.get("uri").toString().startsWith("kestra://"), is(true));
    }

    @Test
    @LoadFlows("flows/typesafe-batch-kill.yaml")
    void evaluateBatchKillEndsKilled() throws Exception {
        // Regression test for the kill race: a slow batch killed mid-flight through the
        // worker must terminate as KILLED, not FAILED. Twelve records with an 8s mock
        // delay (concurrency 2) keep the execution RUNNING long enough to kill it.
        server.resetAll();
        server.stubFor(post(urlEqualTo("/v1/systemone"))
            .willReturn(aResponse().withStatus(200).withBody(RESPONSE_BODY).withFixedDelay(8000)));

        Execution running = runnerUtils.runOneUntilRunning(
            "main", "io.kestra.tests", "typesafe-batch-kill", null,
            (f, e) -> Map.of("baseUrl", server.baseUrl()),
            Duration.ofSeconds(60)
        );

        Execution killed = runnerUtils.killExecution(running);
        Execution terminal = runnerUtils.awaitExecution(
            execution -> execution.getState().isTerminated(),
            killed,
            Duration.ofSeconds(60)
        );

        assertThat(terminal.getState().getCurrent(), is(State.Type.KILLED));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
