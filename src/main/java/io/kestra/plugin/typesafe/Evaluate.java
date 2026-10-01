package io.kestra.plugin.typesafe;

import java.util.Map;

import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Evaluate content with TypeSafe",
    description = "Evaluates a state against a map of typed questions via the TypeSafe System One API and returns one structured answer per question."
)
@Plugin(
    examples = {
        @Example(
            title = "Evaluate urgency and department",
            full = true,
            code = """
                id: typesafe_evaluate
                namespace: company.team

                tasks:
                  - id: evaluate
                    type: io.kestra.plugin.typesafe.Evaluate
                    apiKey: "{{ secret('TYPESAFE_API_KEY') }}"
                    model: jev-latest
                    state: Help! My payouts have been failing for 3 days.
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
                          sales: Pricing, upgrades, new accounts
                """
        ),
        @Example(
            title = "Rate content with a score question",
            full = true,
            code = """
                id: typesafe_score
                namespace: company.team

                tasks:
                  - id: score
                    type: io.kestra.plugin.typesafe.Evaluate
                    apiKey: "{{ secret('TYPESAFE_API_KEY') }}"
                    state: "Customer is very frustrated with the service"
                    questions:
                      frustration:
                        type: SCORE
                        instructions: How frustrated is the customer?
                        levels:
                          - Calm
                          - Frustrated
                          - Very angry
                """
        )
    },
    metrics = {
        @Metric(
            name = "input.tokens",
            type = Counter.TYPE,
            unit = "tokens",
            description = "Number of input tokens reported by the TypeSafe API for the request."
        ),
        @Metric(
            name = "output.tokens",
            type = Counter.TYPE,
            unit = "tokens",
            description = "Number of output tokens reported by the TypeSafe API for the request."
        )
    }
)
public class Evaluate extends AbstractTypeSafe implements RunnableTask<Evaluate.Output> {
    @Schema(
        title = "State",
        description = "The content to evaluate: a plain string for text, or structured data (object or array) for things like chat logs or records.",
        anyOf = { String.class, Map.class, java.util.List.class }
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Object> state;

    @Override
    public Output run(RunContext runContext) throws Exception {
        RenderedShared shared = renderShared(runContext);
        Object rState = this.state == null
            ? orThrowStateEmpty()
            : runContext.render(this.state).as(Object.class)
                .filter(value -> !(value instanceof String str && str.isBlank()))
                .orElseThrow(() -> new IllegalArgumentException(
                    "Invalid 'state': the content to evaluate is empty. Fix: set 'state' to a valid string, object or array, "
                        + "e.g. `state: \"Help! My payouts have been failing for 3 days.\"`."
                ));
        validateQuestions(shared.questions());

        HttpClient client = newClient(runContext);
        try {
            TypeSafeResponse response = evaluateState(
                runContext, client,
                shared.baseUrl(), shared.apiKey(), shared.model(),
                rState, shared.questions()
            );
            emitUsageMetrics(runContext, response.getUsage());
            return Output.builder()
                .answers(response.getAnswers())
                .model(response.getModel())
                .build();
        } finally {
            releaseClient();
        }
    }

    private static Object orThrowStateEmpty() {
        throw new IllegalArgumentException(
            "Invalid 'state': the content to evaluate is empty. Fix: set 'state' to a valid string, object or array, "
                + "e.g. `state: \"Help! My payouts have been failing for 3 days.\"`."
        );
    }

    @lombok.Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Answers",
            description = "One answer per question, keyed by the same ids used in `questions`."
        )
        private final Map<String, Answer> answers;

        @Schema(
            title = "Model",
            description = "The versioned model id that performed the evaluation."
        )
        private final String model;
    }
}
