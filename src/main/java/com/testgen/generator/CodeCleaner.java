package com.testgen.generator;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM çıktısını temizleyen ve parse eden yardımcı sınıf.
 * LLM bazen markdown code fence, açıklama metni ekler – bunları temizler.
 */
@Slf4j
public final class CodeCleaner {

    private static final Pattern FEATURE_BLOCK = Pattern.compile(
            "```(?:gherkin|feature|karate)?\\s*\\n?(Feature:.+?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "```(?:java)?\\s*\\n?((?:package|import|public|//)[\\s\\S]+?)```",
            Pattern.DOTALL);

    private static final Pattern CLASS_NAME = Pattern.compile(
            "(?:public\\s+class|public\\s+abstract\\s+class)\\s+(\\w+)");

    private static final Pattern CLASS_START = Pattern.compile(
            "(?m)^\\s*public\\s+(?:abstract\\s+)?class\\s+\\w+");

    private CodeCleaner() {}

    /**
     * Feature dosyası içeriğini markdown fence'lerden temizler.
     */
    public static String cleanFeatureContent(String raw) {
        if (raw == null) return "";

        // Markdown code block var mı?
        Matcher m = FEATURE_BLOCK.matcher(raw);
        if (m.find()) {
            return m.group(1).strip();
        }

        // Feature: ile başlıyorsa direkt kullan
        String stripped = raw.strip();
        if (stripped.startsWith("Feature:")) {
            return stripped;
        }

        // Genel temizleme
        return raw.replaceAll("```[a-z]*\\n?", "").strip();
    }

    /**
     * Java kaynak içeriğini markdown fence'lerden temizler.
     */
    public static String cleanJavaContent(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("```java\\n?", "")
                  .replaceAll("```\\n?", "")
                  .strip();
    }

    /**
     * LLM çıktısından birden fazla Java sınıfını ayıklar.
     * Her ```java ... ``` bloğunu ayrı bir sınıf olarak döndürür.
     */
    public static List<JavaClassContent> splitJavaClasses(String raw) {
        List<JavaClassContent> result = new ArrayList<>();

        Matcher m = JAVA_BLOCK.matcher(raw);
        while (m.find()) {
            String classContent = m.group(1).strip();
            result.addAll(splitJavaBlock(classContent));
        }

        return result;
    }

    private static List<JavaClassContent> splitJavaBlock(String block) {
        List<JavaClassContent> result = new ArrayList<>();
        Matcher starts = CLASS_START.matcher(block);
        List<Integer> positions = new ArrayList<>();
        while (starts.find()) {
            positions.add(starts.start());
        }

        if (positions.size() <= 1) {
            result.add(new JavaClassContent(extractClassName(block), block));
            return result;
        }

        String sharedPrefix = block.substring(0, positions.get(0)).stripTrailing();
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = i + 1 < positions.size() ? positions.get(i + 1) : block.length();
            String content = block.substring(start, end).strip();
            if (!sharedPrefix.isBlank()) {
                content = sharedPrefix + "\n\n" + content;
            }
            result.add(new JavaClassContent(extractClassName(content), content));
        }
        return result;
    }

    private static String extractClassName(String javaContent) {
        Matcher m = CLASS_NAME.matcher(javaContent);
        if (m.find()) {
            return m.group(1);
        }
        return "GeneratedTest_" + System.currentTimeMillis();
    }
}
