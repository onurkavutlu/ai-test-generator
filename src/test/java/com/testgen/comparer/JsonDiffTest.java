package com.testgen.comparer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class JsonDiffTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String content) throws Exception {
        return mapper.readTree(content);
    }

    @Test
    public void identicalObjectsProduceNoDifferences() throws Exception {
        JsonNode a = json("{\"id\":1,\"name\":\"pet\",\"tags\":[\"a\",\"b\"]}");
        JsonNode b = json("{\"name\":\"pet\",\"id\":1,\"tags\":[\"a\",\"b\"]}");
        assertTrue(JsonDiff.diff(a, b, Set.of()).isEmpty());
    }

    @Test
    public void valueMismatchIsReported() throws Exception {
        JsonNode a = json("{\"price\":10}");
        JsonNode b = json("{\"price\":20}");
        List<FieldDifference> diffs = JsonDiff.diff(a, b, Set.of());
        assertEquals(1, diffs.size());
        assertEquals("/price", diffs.get(0).path());
        assertEquals(FieldDifference.DifferenceType.VALUE_MISMATCH, diffs.get(0).type());
        assertEquals("10", diffs.get(0).valueA());
        assertEquals("20", diffs.get(0).valueB());
    }

    @Test
    public void missingFieldsAreReportedOnBothSides() throws Exception {
        JsonNode a = json("{\"onlyInA\":1,\"common\":true}");
        JsonNode b = json("{\"onlyInB\":2,\"common\":true}");
        List<FieldDifference> diffs = JsonDiff.diff(a, b, Set.of());
        assertEquals(2, diffs.size());
        assertTrue(diffs.stream().anyMatch(d ->
                d.path().equals("/onlyInA") && d.type() == FieldDifference.DifferenceType.MISSING_IN_B));
        assertTrue(diffs.stream().anyMatch(d ->
                d.path().equals("/onlyInB") && d.type() == FieldDifference.DifferenceType.MISSING_IN_A));
    }

    @Test
    public void typeMismatchIsReported() throws Exception {
        JsonNode a = json("{\"id\":\"1\"}");
        JsonNode b = json("{\"id\":1}");
        List<FieldDifference> diffs = JsonDiff.diff(a, b, Set.of());
        assertEquals(1, diffs.size());
        assertEquals(FieldDifference.DifferenceType.TYPE_MISMATCH, diffs.get(0).type());
    }

    @Test
    public void arrayLengthAndElementDiffs() throws Exception {
        JsonNode a = json("{\"items\":[{\"id\":1},{\"id\":2},{\"id\":3}]}");
        JsonNode b = json("{\"items\":[{\"id\":1},{\"id\":99}]}");
        List<FieldDifference> diffs = JsonDiff.diff(a, b, Set.of());
        assertTrue(diffs.stream().anyMatch(d ->
                d.path().equals("/items") && d.type() == FieldDifference.DifferenceType.ARRAY_LENGTH_MISMATCH));
        assertTrue(diffs.stream().anyMatch(d ->
                d.path().equals("/items/1/id") && d.type() == FieldDifference.DifferenceType.VALUE_MISMATCH));
    }

    @Test
    public void ignoredFieldNameIsSkipped() throws Exception {
        JsonNode a = json("{\"timestamp\":\"2026-01-01\",\"data\":{\"timestamp\":\"x\",\"id\":1}}");
        JsonNode b = json("{\"timestamp\":\"2026-02-02\",\"data\":{\"timestamp\":\"y\",\"id\":1}}");
        assertTrue(JsonDiff.diff(a, b, Set.of("timestamp")).isEmpty());
    }

    @Test
    public void ignoredPointerPathIsSkipped() throws Exception {
        JsonNode a = json("{\"data\":{\"id\":1},\"id\":5}");
        JsonNode b = json("{\"data\":{\"id\":2},\"id\":5}");
        List<FieldDifference> diffs = JsonDiff.diff(a, b, Set.of("/data/id"));
        assertTrue(diffs.isEmpty());
    }

    @Test
    public void nestedDifferencesUseJsonPointerPaths() throws Exception {
        JsonNode a = json("{\"data\":{\"pet\":{\"status\":\"available\"}}}");
        JsonNode b = json("{\"data\":{\"pet\":{\"status\":\"sold\"}}}");
        List<FieldDifference> diffs = JsonDiff.diff(a, b, Set.of());
        assertEquals(1, diffs.size());
        assertEquals("/data/pet/status", diffs.get(0).path());
    }

    @Test
    public void differencesAreCappedAtMax() throws Exception {
        StringBuilder aBuilder = new StringBuilder("{");
        StringBuilder bBuilder = new StringBuilder("{");
        for (int i = 0; i < JsonDiff.MAX_DIFFERENCES + 50; i++) {
            if (i > 0) {
                aBuilder.append(",");
                bBuilder.append(",");
            }
            aBuilder.append("\"f").append(i).append("\":1");
            bBuilder.append("\"f").append(i).append("\":2");
        }
        JsonNode a = json(aBuilder.append("}").toString());
        JsonNode b = json(bBuilder.append("}").toString());
        assertEquals(JsonDiff.MAX_DIFFERENCES, JsonDiff.diff(a, b, Set.of()).size());
    }
}
