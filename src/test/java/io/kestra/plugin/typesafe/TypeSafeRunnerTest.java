package io.kestra.plugin.typesafe;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Executes plugin tasks through the real in-memory runner, verifying task registration,
 * flow-level YAML-style configuration and execution outputs.
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

    @Inject
    private FlowRepositoryInterface flowRepository;

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
    void evaluateFlow() throws Exception {
        Evaluate evaluate = Evaluate.builder()
            .id("evaluate")
            .type(Evaluate.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .state(Property.ofValue("Help! My payouts have been failing for 3 days."))
            .questions(Property.ofValue(Map.of(
                "is_urgent", Question.builder()
                    .type(QuestionType.NOUL)
                    .instructions("Does this convey urgency?")
                    .build()
            )))
            .build();

        Flow flow = Flow.builder()
            .id("typesafe-evaluate")
            .namespace("io.kestra.tests")
            .tasks(List.of(evaluate))
            .build();
        flowRepository.create(io.kestra.core.models.flows.GenericFlow.of(flow));

        Execution execution = runnerUtils.runOne(null, "io.kestra.tests", "typesafe-evaluate");

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
        assertThat(execution.getTaskRunList().size(), is(1));

        Map<String, Object> outputs = execution.getTaskRunList().getFirst().getOutputs();
        assertThat(outputs.get("model"), is("jev-1.13.0"));
        Map<String, Object> answers = asMap(outputs.get("answers"));
        assertThat(asMap(answers.get("is_urgent")).get("noul"), is(0.95));
    }

    @Test
    void evaluateWithUpstreamQuestions() throws Exception {
        String flowSource = """
            id: typesafe-upstream
            namespace: io.kestra.tests

            inputs:
              - id: baseUrl
                type: STRING

            tasks:
              - id: producer
                type: io.kestra.plugin.core.output.OutputValues
                values:
                  is_urgent:
                    type: noul
                    instructions: Does this convey urgency?
              - id: evaluate
                type: io.kestra.plugin.typesafe.Evaluate
                apiKey: test-key
                baseUrl: "{{ inputs.baseUrl }}"
                model: jev-latest
                state: Help! My payouts have been failing for 3 days.
                questions: "{{ outputs.producer.values }}"
            """;
        flowRepository.create(io.kestra.core.models.flows.GenericFlow.fromYaml(null, flowSource));

        Execution execution = runnerUtils.runOne(
            null, "io.kestra.tests", "typesafe-upstream", null,
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
    void evaluateBatchFlow() throws Exception {
        EvaluateBatch batch = EvaluateBatch.builder()
            .id("batch")
            .type(EvaluateBatch.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .baseUrl(Property.ofValue(server.baseUrl()))
            .model(Property.ofValue("jev-latest"))
            .from(List.of("first", "second"))
            .concurrency(Property.ofValue(2))
            .questions(Property.ofValue(Map.of(
                "is_urgent", Question.builder()
                    .type(QuestionType.NOUL)
                    .instructions("Does this convey urgency?")
                    .build()
            )))
            .build();

        Flow flow = Flow.builder()
            .id("typesafe-batch")
            .namespace("io.kestra.tests")
            .tenantId("test-tenant")
            .tasks(List.of(batch))
            .build();
        flowRepository.create(io.kestra.core.models.flows.GenericFlow.of(flow));

        Execution execution = runnerUtils.runOne("test-tenant", "io.kestra.tests", "typesafe-batch");

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));

        Map<String, Object> outputs = execution.getTaskRunList().getFirst().getOutputs();
        assertThat(outputs.get("count"), is(2));
        assertThat(outputs.get("model"), is("jev-1.13.0"));
        assertThat(outputs.get("uri").toString().startsWith("kestra://"), is(true));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
