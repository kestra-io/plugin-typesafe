package io.kestra.plugin.typesafe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuestionTest {
    @Test
    void noulWithoutCriteriaIsValid() {
        Question question = Question.builder()
            .type(QuestionType.NOUL)
            .instructions("Does this convey urgency?")
            .build();

        question.validate("is_urgent");

        Map<String, Object> api = question.toApiMap();
        assertThat(api.get("type"), is("noul"));
        assertThat(api.get("instructions"), is("Does this convey urgency?"));
        assertThat(api.containsKey("criteria"), is(false));
    }

    @Test
    void noulWithCriteriaMapsToTrueAndFalse() {
        Question question = Question.builder()
            .type(QuestionType.NOUL)
            .instructions("Does this convey urgency?")
            .whenTrue("Explicitly time-sensitive")
            .whenFalse("No urgency expressed")
            .build();

        question.validate("is_urgent");

        Map<String, Object> criteria = asMap(question.toApiMap().get("criteria"));
        assertThat(criteria.get("true"), is("Explicitly time-sensitive"));
        assertThat(criteria.get("false"), is("No urgency expressed"));
    }

    @Test
    void choiceWithNullOptionKeepsNullInCriteria() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("billing", "Payments, invoicing, refunds");
        options.put("technical", null);
        Question question = Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Which team should handle this?")
            .options(options)
            .build();

        question.validate("department");

        Map<String, Object> api = question.toApiMap();
        assertThat(api.get("type"), is("choice"));
        Map<String, Object> criteria = asMap(api.get("criteria"));
        assertThat(criteria.get("billing"), is("Payments, invoicing, refunds"));
        assertThat(criteria.containsKey("technical"), is(true));
        assertThat(criteria.get("technical"), nullValue());
    }

    @Test
    void scoreMapsLevelsToCriteriaArray() {
        Question question = Question.builder()
            .type(QuestionType.SCORE)
            .instructions("How frustrated is the customer?")
            .levels(List.of("Calm", "Frustrated", "Very angry"))
            .build();

        question.validate("frustration");

        Map<String, Object> api = question.toApiMap();
        assertThat(api.get("type"), is("score"));
        assertThat(api.get("criteria"), is(List.of("Calm", "Frustrated", "Very angry")));
    }

    @Test
    void missingTypeIdentifiesQuestionAndFix() {
        Question question = Question.builder()
            .instructions("Something?")
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("q1"));
        assertThat(e.getMessage(), containsString("'q1'"));
        assertThat(e.getMessage(), containsString("'type'"));
    }

    @Test
    void missingInstructionsIdentifiesQuestionAndFix() {
        Question question = Question.builder()
            .type(QuestionType.NOUL)
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("q1"));
        assertThat(e.getMessage(), containsString("'q1'"));
        assertThat(e.getMessage(), containsString("'instructions'"));
    }

    @Test
    void optionsOnNoulIsRejected() {
        Question question = Question.builder()
            .type(QuestionType.NOUL)
            .instructions("Urgent?")
            .options(Map.of("a", "b"))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("is_urgent"));
        assertThat(e.getMessage(), containsString("'is_urgent'"));
        assertThat(e.getMessage(), containsString("'options'"));
        assertThat(e.getMessage(), containsString("'NOUL'"));
    }

    @Test
    void levelsOnChoiceIsRejected() {
        Question question = Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Pick one.")
            .options(Map.of("a", "b"))
            .levels(List.of("L1", "L2"))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("dept"));
        assertThat(e.getMessage(), containsString("'dept'"));
        assertThat(e.getMessage(), containsString("'levels'"));
    }

    @Test
    void whenTrueOnScoreIsRejected() {
        Question question = Question.builder()
            .type(QuestionType.SCORE)
            .instructions("Rate it.")
            .levels(List.of("Low", "High"))
            .whenTrue("yes")
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("rating"));
        assertThat(e.getMessage(), containsString("'rating'"));
        assertThat(e.getMessage(), containsString("whenTrue"));
    }

    @Test
    void choiceWithoutOptionsIsRejected() {
        Question question = Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Pick one.")
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("dept"));
        assertThat(e.getMessage(), containsString("'dept'"));
        assertThat(e.getMessage(), containsString("'options'"));
    }

    @Test
    void choiceWithMoreThan255OptionsIsRejected() {
        Map<String, Object> options = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) {
            options.put("option-" + i, "Rubric " + i);
        }
        Question question = Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Pick one.")
            .options(options)
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("dept"));
        assertThat(e.getMessage(), containsString("'dept'"));
        assertThat(e.getMessage(), containsString("256"));
        assertThat(e.getMessage(), containsString("255"));
    }

    @Test
    void choiceWithExactly255OptionsIsValid() {
        Map<String, Object> options = new LinkedHashMap<>();
        for (int i = 0; i < 255; i++) {
            options.put("option-" + i, "Rubric " + i);
        }
        Question.builder()
            .type(QuestionType.CHOICE)
            .instructions("Pick one.")
            .options(options)
            .build()
            .validate("dept");
    }

    @Test
    void scoreWithOneLevelIsRejected() {
        Question question = Question.builder()
            .type(QuestionType.SCORE)
            .instructions("Rate it.")
            .levels(List.of("Only"))
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("rating"));
        assertThat(e.getMessage(), containsString("'rating'"));
        assertThat(e.getMessage(), containsString("2"));
    }

    @Test
    void scoreWithElevenLevelsIsRejected() {
        List<Object> levels = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            levels.add("Level " + i);
        }
        Question question = Question.builder()
            .type(QuestionType.SCORE)
            .instructions("Rate it.")
            .levels(levels)
            .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> question.validate("rating"));
        assertThat(e.getMessage(), containsString("'rating'"));
        assertThat(e.getMessage(), containsString("11"));
        assertThat(e.getMessage(), containsString("10"));
    }

    @Test
    void scoreBoundariesAreValid() {
        Question.builder().type(QuestionType.SCORE).instructions("R?").levels(List.of("A", "B")).build().validate("two");
        List<Object> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("Level " + i);
        }
        Question.builder().type(QuestionType.SCORE).instructions("R?").levels(ten).build().validate("ten");
    }

    @Test
    void questionTypeIsLowercaseAndCaseInsensitive() {
        assertThat(QuestionType.fromValue("NOUL"), is(QuestionType.NOUL));
        assertThat(QuestionType.fromValue("Choice"), is(QuestionType.CHOICE));
        assertThat(QuestionType.NOUL.getValue(), is("noul"));
        assertThat(QuestionType.CHOICE.getValue(), is("choice"));
        assertThat(QuestionType.SCORE.getValue(), is("score"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> QuestionType.fromValue("maybe"));
        assertThat(e.getMessage(), containsString("NOUL, CHOICE, SCORE"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
