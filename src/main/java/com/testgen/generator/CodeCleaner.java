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
     * Feature dosyası içeriğini markdown fence'lerden temizler ve @testCaseLLM tag'lerini ekler.
     */
    public static String cleanFeatureContent(String raw) {
        if (raw == null) return "";

        String cleaned;
        // Markdown code block var mı?
        Matcher m = FEATURE_BLOCK.matcher(raw);
        if (m.find()) {
            cleaned = m.group(1).strip();
        } else {
            // Fence yoksa: fence kalıntılarını sil, LLM'in kod öncesi açıklama metnini at
            cleaned = stripToFeatureStart(raw.replaceAll("```[a-z]*\\n?", ""));
        }
        return injectTestCaseLlmTag(cleaned);
    }

    /**
     * LLM'in "İşte düzeltilmiş kod:" gibi açıklama metinlerini atar —
     * içerik ilk Feature satırından (hemen üstündeki bitişik tag satırları dahil) başlar.
     * Feature satırı yoksa içerik olduğu gibi döner.
     */
    private static String stripToFeatureStart(String text) {
        String[] lines = text.split("\\r?\\n");
        int featureIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("Feature:")) {
                featureIdx = i;
                break;
            }
        }
        if (featureIdx < 0) {
            return text.strip();
        }
        int start = featureIdx;
        while (start > 0 && lines[start - 1].trim().startsWith("@")) {
            start--;
        }
        return String.join("\n",
                java.util.Arrays.copyOfRange(lines, start, lines.length)).strip();
    }

    /**
     * Feature dosyasındaki Feature ve Scenario satırlarına @testCaseLLM tag'i enjekte eder.
     */
    public static String injectTestCaseLlmTag(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\\r?\\n")));

        // 0. Dangling tag temizliği: Gherkin'de tag satırının HEMEN altında Feature/Scenario/
        //    Scenario Outline/Examples (veya başka bir tag satırı) olmalı. LLM bazen tag'i
        //    senaryonun sonuna koyar — bu Karate'de parse hatası üretir ("no viable alternative").
        for (int i = lines.size() - 1; i >= 0; i--) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("@")) {
                continue;
            }
            String next = i + 1 < lines.size() ? lines.get(i + 1).trim() : "";
            boolean valid = next.startsWith("@")
                    || next.startsWith("Feature:")
                    || next.startsWith("Scenario:")
                    || next.startsWith("Scenario Outline:")
                    || next.startsWith("Examples:");
            if (!valid) {
                lines.remove(i);
            }
        }

        // 1. Feature seviyesinde @testCaseLLM tag'i var mı? Yoksa ekle (lokal kontrol:
        //    Feature satırının hemen üstündeki bitişik tag bloğuna bakılır).
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).trim().startsWith("Feature:")) {
                continue;
            }
            boolean hasFeatureTag = false;
            int insertAt = i;
            for (int j = i - 1; j >= 0 && lines.get(j).trim().startsWith("@"); j--) {
                insertAt = j;
                if (lines.get(j).contains("@testCaseLLM")) {
                    hasFeatureTag = true;
                }
            }
            if (!hasFeatureTag) {
                lines.add(insertAt, "@testCaseLLM");
            }
            break;
        }

        // 2. Her Scenario veya Scenario Outline öncesinde @testCaseLLM tag'ini garanti altına al.

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                boolean hasTag = false;
                int insertIndex = i;
                for (int j = i - 1; j >= 0; j--) {
                    String prevLine = lines.get(j).trim();
                    if (prevLine.startsWith("Scenario:") || prevLine.startsWith("Scenario Outline:") || prevLine.startsWith("Feature:") || prevLine.startsWith("Background:")) {
                        break;
                    }
                    if (prevLine.contains("@testCaseLLM")) {
                        hasTag = true;
                        break;
                    }
                    if (prevLine.startsWith("@")) {
                        insertIndex = j;
                    }
                }
                if (!hasTag) {
                    // Scenario satırının girintisini alıp etikete uygulayalım
                    String indent = line.substring(0, line.indexOf(trimmed));
                    lines.add(insertIndex, indent + "@testCaseLLM");
                    i++; // Eleman eklendiği için endeksi kaydırıyoruz
                }
            }
        }

        return String.join("\n", lines).strip();
    }

    /**
     * Java kaynak içeriğini markdown fence'lerden ve açıklama metinlerinden temizler.
     */
    public static String cleanJavaContent(String raw) {
        if (raw == null) return "";

        // 1. Markdown block formatı varsa (```java ... ```) orayı ayıkla
        Matcher m = JAVA_BLOCK.matcher(raw);
        if (m.find()) {
            return m.group(1).strip();
        }

        // 2. Yoksa, ilk kod satırından (package, import, public class, //) itibaren temizle
        String stripped = raw.strip();
        int firstKeyword = -1;
        for (String kw : new String[]{"package ", "import ", "public class", "public abstract", "public interface", "//"}) {
            int idx = stripped.indexOf(kw);
            if (idx >= 0 && (firstKeyword == -1 || idx < firstKeyword)) {
                firstKeyword = idx;
            }
        }
        if (firstKeyword >= 0) {
            stripped = stripped.substring(firstKeyword).strip();
        }

        return stripped.replaceAll("```[a-z]*\\n?", "").strip();
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
