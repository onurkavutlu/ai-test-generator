package com.testgen.comparer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * İki JsonNode ağacını recursive karşılaştırıp alan bazlı fark listesi üretir.
 *
 * - Objeler: her iki tarafın alan kümeleri birleştirilir; eksik alanlar
 *   MISSING_IN_A / MISSING_IN_B, ortak alanlar recursive diff'lenir.
 * - Array'ler: index bazlı karşılaştırılır; uzunluk farkı ayrıca raporlanır.
 * - Değerler: tip farkı TYPE_MISMATCH, değer farkı VALUE_MISMATCH.
 * - ignoreFields: alan adı ("timestamp") veya tam pointer ("/data/id") ile
 *   eşleşen alanlar diff dışı bırakılır (dinamik alanlar için).
 */
public final class JsonDiff {

    /** UI'ı korumak için istek başına raporlanan maksimum fark sayısı. */
    public static final int MAX_DIFFERENCES = 200;

    private static final int MAX_VALUE_PREVIEW = 500;

    private JsonDiff() {
    }

    public static List<FieldDifference> diff(JsonNode a, JsonNode b, Set<String> ignoreFields) {
        List<FieldDifference> differences = new ArrayList<>();
        Set<String> ignore = ignoreFields == null ? Set.of() : ignoreFields;
        compare(a, b, "", ignore, differences);
        return differences;
    }

    private static void compare(JsonNode a, JsonNode b, String path,
                                Set<String> ignore, List<FieldDifference> out) {
        if (out.size() >= MAX_DIFFERENCES) {
            return;
        }
        if (isIgnored(path, ignore)) {
            return;
        }

        if (a == null || a.isMissingNode()) {
            out.add(new FieldDifference(pointer(path), FieldDifference.DifferenceType.MISSING_IN_A,
                    null, preview(b)));
            return;
        }
        if (b == null || b.isMissingNode()) {
            out.add(new FieldDifference(pointer(path), FieldDifference.DifferenceType.MISSING_IN_B,
                    preview(a), null));
            return;
        }

        if (a.getNodeType() != b.getNodeType()) {
            out.add(new FieldDifference(pointer(path), FieldDifference.DifferenceType.TYPE_MISMATCH,
                    a.getNodeType() + ": " + preview(a), b.getNodeType() + ": " + preview(b)));
            return;
        }

        if (a.isObject()) {
            Set<String> allFields = new TreeSet<>();
            a.fieldNames().forEachRemaining(allFields::add);
            b.fieldNames().forEachRemaining(allFields::add);
            for (String field : allFields) {
                JsonNode childA = a.has(field) ? a.get(field) : null;
                JsonNode childB = b.has(field) ? b.get(field) : null;
                compare(childA, childB, path + "/" + escapePointerSegment(field), ignore, out);
            }
        } else if (a.isArray()) {
            if (a.size() != b.size()) {
                out.add(new FieldDifference(pointer(path),
                        FieldDifference.DifferenceType.ARRAY_LENGTH_MISMATCH,
                        String.valueOf(a.size()), String.valueOf(b.size())));
            }
            int common = Math.min(a.size(), b.size());
            for (int i = 0; i < common; i++) {
                compare(a.get(i), b.get(i), path + "/" + i, ignore, out);
            }
        } else if (!a.equals(b)) {
            out.add(new FieldDifference(pointer(path), FieldDifference.DifferenceType.VALUE_MISMATCH,
                    preview(a), preview(b)));
        }
    }

    private static boolean isIgnored(String path, Set<String> ignore) {
        if (path.isEmpty() || ignore.isEmpty()) {
            return false;
        }
        if (ignore.contains(path)) {
            return true;
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return ignore.contains(lastSegment);
    }

    private static String pointer(String path) {
        return path.isEmpty() ? "/" : path;
    }

    /** RFC 6901: ~ → ~0, / → ~1 */
    private static String escapePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static String preview(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        String text = node.isTextual() ? node.asText() : node.toString();
        return text.length() > MAX_VALUE_PREVIEW ? text.substring(0, MAX_VALUE_PREVIEW) + "…" : text;
    }
}
