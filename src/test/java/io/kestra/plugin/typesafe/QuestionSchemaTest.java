package io.kestra.plugin.typesafe;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.junit.annotations.KestraTest;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the generated JSON schema exposes the {@code questions} map with {@link Question}
 * values carrying every question field, so the Kestra editor can autocomplete them.
 */
@KestraTest
class QuestionSchemaTest {
    private static final Set<String> EXPECTED_QUESTION_FIELDS = Set.of(
        "type", "instructions", "options", "levels", "whenTrue", "whenFalse"
    );

    @Inject
    private JsonSchemaGenerator jsonSchemaGenerator;

    @Test
    void evaluateQuestionsSchema() {
        assertQuestionsSchema(Evaluate.class);
    }

    @Test
    void evaluateBatchQuestionsSchema() {
        assertQuestionsSchema(EvaluateBatch.class);
    }

    @SuppressWarnings("unchecked")
    private void assertQuestionsSchema(Class<?> taskClass) {
        Map<String, Object> schemas = jsonSchemaGenerator.schemas(taskClass);
        Map<String, Object> definitions = (Map<String, Object>) schemas.get("definitions");

        String taskKey = "io.kestra.plugin.typesafe." + taskClass.getSimpleName();
        Map<String, Object> task = (Map<String, Object>) definitions.get(taskKey);
        Map<String, Object> properties = (Map<String, Object>) task.get("properties");
        Map<String, Object> questions = (Map<String, Object>) properties.get("questions");

        assertThat(questions.get("type"), is("object"));
        Map<String, Object> additionalProperties = (Map<String, Object>) questions.get("additionalProperties");
        assertThat(additionalProperties.get("$ref"), is("#/definitions/io.kestra.plugin.typesafe.Question"));

        Map<String, Object> question = (Map<String, Object>) definitions.get("io.kestra.plugin.typesafe.Question");
        Map<String, Object> questionProperties = (Map<String, Object>) question.get("properties");
        assertThat(questionProperties.keySet().containsAll(EXPECTED_QUESTION_FIELDS), is(true));
    }
}
