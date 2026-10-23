package patchbaytest.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** Numbers are equal by value (2 equals 2.0); everything else as Jackson compares it. Key order never matters. */
    private static final Comparator<JsonNode> NUMBERS_BY_VALUE = (a, b) -> a.isNumber() && b.isNumber()
            ? a.decimalValue().compareTo(b.decimalValue())
            : a.equals(b) ? 0 : 1;

    private Json() {
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not JSON: " + json, e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Asserts that {@code actual} is exactly {@code expectedJson}: no fields missing, none extra. */
    public static void assertJsonEquals(JsonNode actual, String expectedJson) {
        JsonNode expected = parse(expectedJson);
        assertThat(actual.equals(NUMBERS_BY_VALUE, expected))
                .as("expected%n  %s%nbut was%n  %s", expected, actual)
                .isTrue();
    }
}
