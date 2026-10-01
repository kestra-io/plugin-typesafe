package io.kestra.plugin.typesafe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single TypeSafe question.
 *
 * <p>The Kestra-facing fields {@code options}, {@code levels}, {@code whenTrue} and {@code whenFalse}
 * are mapped to the TypeSafe API {@code criteria} field when the request is built.
 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(
    title = "TypeSafe question",
    description = "A typed question evaluated against a state. Use `options` for `CHOICE`, `levels` for `SCORE`, and `whenTrue`/`whenFalse` for `NOUL`."
)
public class Question {
    @Schema(
        title = "Question type",
        description = "The question type: `NOUL`, `CHOICE` or `SCORE`."
    )
    private QuestionType type;

    @Schema(
        title = "Instructions",
        description = "The question to evaluate. Accepts a string or structured JSON (object or array); put the question in one field and data it refers to in the others.",
        anyOf = { String.class, Map.class, List.class }
    )
    private Object instructions;

    @Schema(
        title = "Choice options",
        description = "Only for `CHOICE` questions: a map of option name to rubric description (use null when an option needs no extra detail). Maximum 255 options."
    )
    private Map<String, Object> options;

    @Schema(
        title = "Score levels",
        description = "Only for `SCORE` questions: an ordered array of level descriptions. At least 2 levels, at most 10."
    )
    private List<Object> levels;

    @Schema(
        title = "Noul 'yes' criteria",
        description = "Only for `NOUL` questions: what a yes answer (value near 1) means. Accepts a string or structured JSON."
    )
    private Object whenTrue;

    @Schema(
        title = "Noul 'no' criteria",
        description = "Only for `NOUL` questions: what a no answer (value near 0) means. Accepts a string or structured JSON."
    )
    private Object whenFalse;

    /**
     * Validates this question, throwing an {@link IllegalArgumentException} that identifies the
     * question id, explains what is invalid and explains how to fix it.
     *
     * @param id the caller-chosen question id used as the key in the {@code questions} map
     */
    public void validate(String id) {
        String prefix = "Invalid question '" + id + "': ";
        if (type == null) {
            throw new IllegalArgumentException(prefix + "missing required field 'type'. Fix: set 'type' to one of: NOUL, CHOICE, SCORE.");
        }
        if (instructions == null) {
            throw new IllegalArgumentException(prefix + "missing required field 'instructions'. Fix: set 'instructions' to the question to evaluate (a string or structured JSON).");
        }
        if (options != null && type != QuestionType.CHOICE) {
            throw new IllegalArgumentException(prefix + "field 'options' is only valid for 'CHOICE' questions, but this question has type '" + type.name() + "'. Fix: remove 'options' or change 'type' to 'CHOICE'.");
        }
        if (levels != null && type != QuestionType.SCORE) {
            throw new IllegalArgumentException(prefix + "field 'levels' is only valid for 'SCORE' questions, but this question has type '" + type.name() + "'. Fix: remove 'levels' or change 'type' to 'SCORE'.");
        }
        if ((whenTrue != null || whenFalse != null) && type != QuestionType.NOUL) {
            throw new IllegalArgumentException(prefix + "fields 'whenTrue'/'whenFalse' are only valid for 'NOUL' questions, but this question has type '" + type.name() + "'. Fix: remove 'whenTrue'/'whenFalse' or change 'type' to 'NOUL'.");
        }
        if (type == QuestionType.CHOICE) {
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException(prefix + "missing required field 'options'. Fix: set 'options' to a map of option name to rubric description.");
            }
            if (options.size() > 255) {
                throw new IllegalArgumentException(prefix + "has " + options.size() + " options, but the TypeSafe API accepts at most 255 options per 'CHOICE' question. Fix: reduce 'options' to 255 entries or fewer.");
            }
        }
        if (type == QuestionType.SCORE) {
            if (levels == null || levels.isEmpty()) {
                throw new IllegalArgumentException(prefix + "missing required field 'levels'. Fix: set 'levels' to an ordered array of level descriptions.");
            }
            if (levels.size() < 2) {
                throw new IllegalArgumentException(prefix + "has " + levels.size() + " level(s), but a 'SCORE' question needs at least 2 levels. Fix: add at least one more entry to 'levels'.");
            }
            if (levels.size() > 10) {
                throw new IllegalArgumentException(prefix + "has " + levels.size() + " levels, but the TypeSafe API accepts at most 10 levels per 'SCORE' question. Fix: reduce 'levels' to 10 entries or fewer.");
            }
        }
    }

    /**
     * Builds the TypeSafe API representation of this question, mapping the Kestra-facing
     * {@code options}, {@code levels}, {@code whenTrue} and {@code whenFalse} fields to {@code criteria}.
     *
     * @return the API question object, safe to serialize as JSON
     */
    public Map<String, Object> toApiMap() {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("type", type.getValue());
        api.put("instructions", instructions);
        switch (type) {
            case CHOICE -> api.put("criteria", new LinkedHashMap<>(options));
            case SCORE -> api.put("criteria", new ArrayList<>(levels));
            case NOUL -> {
                if (whenTrue != null || whenFalse != null) {
                    Map<String, Object> criteria = new LinkedHashMap<>();
                    if (whenTrue != null) {
                        criteria.put("true", whenTrue);
                    }
                    if (whenFalse != null) {
                        criteria.put("false", whenFalse);
                    }
                    api.put("criteria", criteria);
                }
            }
        }
        return api;
    }
}
