package io.kestra.plugin.typesafe;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The type of a TypeSafe question. Serialized in lowercase as required by the TypeSafe API.
 */
@Schema(
    title = "Question type",
    description = "The TypeSafe question type: `NOUL` (yes/no probability), `CHOICE` (pick one option) or `SCORE` (rate along ordered levels)."
)
public enum QuestionType {
    NOUL("noul"),
    CHOICE("choice"),
    SCORE("score");

    private final String value;

    QuestionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static QuestionType fromValue(String value) {
        if (value != null) {
            for (QuestionType type : values()) {
                if (type.value.equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException(
            "Invalid question type '" + value + "'. Fix: set 'type' to one of: NOUL, CHOICE, SCORE."
        );
    }
}
