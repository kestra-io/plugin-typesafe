package io.kestra.plugin.typesafe;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A TypeSafe answer, carrying a {@code type} matching its question.
 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    title = "TypeSafe answer",
    description = "The structured answer for one question. Only the fields matching the question type are populated."
)
public class Answer {
    @Schema(
        title = "Answer type",
        description = "Matches the question type: `NOUL`, `CHOICE` or `SCORE`."
    )
    private QuestionType type;

    @Schema(
        title = "Noul probability",
        description = "Only for `NOUL` answers: the probability the answer is yes, on a scale from 0 (no) to 1 (yes)."
    )
    private Double noul;

    @Schema(
        title = "Choice selection",
        description = "Only for `CHOICE` answers: the highest-probability option."
    )
    private String choice;

    @Schema(
        title = "Score value",
        description = "Only for `SCORE` answers: the probability-weighted answer across the levels; can land between levels."
    )
    private Double score;

    @Schema(
        title = "Probabilities",
        description = "Only for `CHOICE` and `SCORE` answers: every option (or level) mapped to its probability (floats that sum to 1)."
    )
    private Map<String, Double> probabilities;

    @Schema(
        title = "Score legend",
        description = "Only for `SCORE` answers: each level number mapped back to its description."
    )
    private Map<String, String> legend;

    @Schema(
        title = "Confidence",
        description = "Only for `CHOICE` and `SCORE` answers: how certain the model is, between 0 and 1, derived from the probability distribution."
    )
    private Double confidence;
}
