package com.testgen.runner;

import com.testgen.model.TestFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Üretilen Java test projesinin iskeleti — 462 satırlık, hiç testi olmayan en büyük sınıf.
 *
 * <p>Buradaki hataların hepsi <b>derleme zamanında</b> patlar ve kullanıcıya
 * "0/0 FAILED" olarak görünür; kök neden (yanlış dosya adı, silinmiş Page Object)
 * loglara gömülür. Sınıfın kendi yorumları iki yaşanmış hatayı anlatıyor ve ikisi de
 * burada kilitleniyor:
 * <ul>
 *   <li>public sınıf adı ile dosya adı ayrışınca javac tüm sınıfı reddediyordu,</li>
 *   <li>temizlik adımı Page Object'leri de silince testler
 *       "cannot find symbol: class XPage" ile derlenemiyordu.</li>
 * </ul>
 */
class GeneratedJavaTestProjectServiceTest {

    @TempDir
    Path tempDir;

    private GeneratedJavaTestProjectService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedJavaTestProjectService();
        ReflectionTestUtils.setField(service, "seleniumOutputPath",
                tempDir.resolve("selenium").toString());
        ReflectionTestUtils.setField(service, "restassuredOutputPath",
                tempDir.resolve("restassured").toString());
        ReflectionTestUtils.setField(service, "seleniumVersion", "4.18.1");
    }

    @Nested
    @DisplayName("Public sınıf adı hizalama")
    class ClassNameAlignment {

        /**
         * Java, public sınıf adının dosya adıyla aynı olmasını ŞART koşar. API'den elle
         * case eklendiğinde ikisi ayrışıyordu ve javac tüm sınıfı reddediyordu.
         */
        @Test
        @DisplayName("Sınıf adı dosya adıyla uyuşmuyorsa içerikteki ad hizalanır")
        void alignsMismatchedClassName() {
            String aligned = GeneratedJavaTestProjectService.alignPublicClassName(
                    "YeniAdTest.java", "public class EskiAdTest { }");

            assertTrue(aligned.contains("class YeniAdTest"), aligned);
            assertFalse(aligned.contains("class EskiAdTest"), aligned);
        }

        @Test
        @DisplayName("Ad zaten uyuşuyorsa içerik aynen döner")
        void leavesMatchingNameUntouched() {
            String content = "public class PetTest { void x() {} }";

            assertEquals(content,
                    GeneratedJavaTestProjectService.alignPublicClassName("PetTest.java", content));
        }

        @Test
        @DisplayName("Java olmayan dosyalarda hizalama yapılmaz")
        void skipsNonJavaFiles() {
            String content = "Feature: pets";

            assertEquals(content,
                    GeneratedJavaTestProjectService.alignPublicClassName("pets.feature", content));
        }

        @Test
        @DisplayName("null ve boş girdilerde çökmez")
        void handlesNullAndBlankInputs() {
            assertEquals(null,
                    GeneratedJavaTestProjectService.alignPublicClassName("X.java", null));
            assertEquals("  ",
                    GeneratedJavaTestProjectService.alignPublicClassName("X.java", "  "));
            assertEquals("içerik",
                    GeneratedJavaTestProjectService.alignPublicClassName(null, "içerik"));
        }

        /**
         * Sadece TAM sözcük eşleşmesi değiştirilmeli. "EskiAdTestHelper" gibi bir ad
         * kısmen değiştirilirse kod bozulur.
         */
        @Test
        @DisplayName("Kısmi ad eşleşmesi bozulmaz, yalnızca tam sözcük değişir")
        void onlyReplacesWholeWordMatches() {
            String aligned = GeneratedJavaTestProjectService.alignPublicClassName(
                    "YeniTest.java",
                    "public class EskiTest { EskiTestHelper h; }");

            assertTrue(aligned.contains("class YeniTest"), aligned);
            assertTrue(aligned.contains("EskiTestHelper"),
                    "Kısmi eşleşme bozulmuş: " + aligned);
        }
    }

    @Nested
    @DisplayName("Proje iskeleti")
    class ProjectScaffolding {

        @Test
        @DisplayName("Selenium projesi pom ve kaynak dizini ile oluşturulur")
        void createsSeleniumProject() {
            Path project = service.ensureProject(TestFramework.SELENIUM);

            assertTrue(Files.exists(project.resolve("pom.xml")), "pom.xml üretilmemiş");
            assertTrue(Files.isDirectory(project), "Proje dizini yok");
        }

        @Test
        @DisplayName("REST Assured projesi de oluşturulur")
        void createsRestAssuredProject() {
            Path project = service.ensureProject(TestFramework.REST_ASSURED);

            assertTrue(Files.exists(project.resolve("pom.xml")));
        }

        @Test
        @DisplayName("Aynı framework için tekrar çağrı yeni proje kurmaz, aynı dizini döner")
        void isIdempotent() {
            Path first = service.ensureProject(TestFramework.SELENIUM);
            Path second = service.ensureProject(TestFramework.SELENIUM);

            assertEquals(first, second);
        }

        @Test
        @DisplayName("Karate için Java projesi istenmez — açık hata verir")
        void rejectsKarateFramework() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.projectDir(TestFramework.KARATE));
        }

        @Test
        @DisplayName("Selenium projesi WebDriver fabrikası kaynağı sağlar")
        void providesDriverFactorySource() {
            String source = GeneratedJavaTestProjectService.driverFactorySource();

            assertNotNull(source);
            assertTrue(source.contains("DriverFactory"), source.substring(0, 80));
            assertTrue(source.contains("WebDriver"));
        }
    }

    @Nested
    @DisplayName("Test kaynağı yazma")
    class WritingTestSources {

        @Test
        @DisplayName("Kaynak dosya yazılır ve içeriği korunur")
        void writesSourceFile() throws Exception {
            Path written = service.writeTestSource(TestFramework.SELENIUM,
                    "LoginTest.java", "public class LoginTest { }");

            assertTrue(Files.exists(written));
            assertTrue(Files.readString(written).contains("class LoginTest"));
        }

        @Test
        @DisplayName("Yazarken sınıf adı dosya adıyla hizalanır")
        void alignsClassNameWhileWriting() throws Exception {
            Path written = service.writeTestSource(TestFramework.SELENIUM,
                    "DogruAdTest.java", "public class YanlisAdTest { }");

            assertTrue(Files.readString(written).contains("class DogruAdTest"));
        }

        @Test
        @DisplayName("Türkçe karakterler UTF-8 olarak korunur")
        void preservesUtf8Characters() throws Exception {
            Path written = service.writeTestSource(TestFramework.SELENIUM,
                    "TurkceTest.java", "public class TurkceTest { String s = \"ürün şifre çğı\"; }");

            assertTrue(Files.readString(written).contains("ürün şifre çğı"));
        }
    }

    @Nested
    @DisplayName("İzole koşum dizini")
    class IsolatedRun {

        /**
         * İzolasyon, LLM'in ürettiği BOZUK bir sınıfın diğer case'lerin derlenmesini
         * engellememesi için var. Kopyalanan dizinde yalnızca hedef test bulunmalı.
         */
        @Test
        @DisplayName("İzole koşum dizini hedef testi içerir")
        void isolatedRunContainsTargetTest() throws Exception {
            Path runDir = service.prepareIsolatedRun(TestFramework.SELENIUM,
                    "run-1", "LoginTest.java", "public class LoginTest { }");

            assertTrue(Files.exists(runDir.resolve("pom.xml")), "İzole projede pom yok");
            try (var walk = Files.walk(runDir)) {
                assertTrue(walk.anyMatch(p -> p.getFileName().toString().equals("LoginTest.java")),
                        "Hedef test izole dizine kopyalanmamış");
            }
        }

        @Test
        @DisplayName("Farklı koşum anahtarları ayrı dizinler üretir")
        void differentRunKeysGiveDifferentDirectories() {
            Path a = service.prepareIsolatedRun(TestFramework.SELENIUM,
                    "run-a", "ATest.java", "public class ATest { }");
            Path b = service.prepareIsolatedRun(TestFramework.SELENIUM,
                    "run-b", "BTest.java", "public class BTest { }");

            assertFalse(a.equals(b), "İzolasyon yok — aynı dizin kullanılmış");
        }

        @Test
        @DisplayName("Koşum dizini temizlenebilir")
        void cleanupRemovesRunDirectory() {
            Path runDir = service.prepareIsolatedRun(TestFramework.SELENIUM,
                    "run-temiz", "XTest.java", "public class XTest { }");

            service.cleanupIsolatedRun(runDir);

            assertFalse(Files.exists(runDir), "İzole dizin silinmemiş — disk şişer");
        }

        @Test
        @DisplayName("Var olmayan dizini temizlemek hata vermez")
        void cleanupOfMissingDirectoryIsSafe() {
            service.cleanupIsolatedRun(tempDir.resolve("hic-olmayan"));
        }
    }

    @Nested
    @DisplayName("Bayat test temizliği")
    class StaleTestCleanup {

        /**
         * Yaşanmış hata: temizlik Page Object'leri de siliyordu ve testler
         * "cannot find symbol: class XPage" ile derlenemiyordu — destek sınıfları
         * case olarak saklanmadığı için koşumda yeniden yazılmıyor.
         */
        @Test
        @DisplayName("Yalnızca *Test.java silinir, destek sınıfları korunur")
        void deletesOnlyTestClassesKeepingSupportSources() throws Exception {
            service.writeTestSource(TestFramework.SELENIUM, "EskiTest.java",
                    "public class EskiTest { }");
            service.writeTestSource(TestFramework.SELENIUM, "LoginPage.java",
                    "public class LoginPage { }");
            service.writeTestSource(TestFramework.SELENIUM, "DriverFactory.java",
                    "public class DriverFactory { }");

            service.cleanTestFiles(TestFramework.SELENIUM);

            Path project = service.projectDir(TestFramework.SELENIUM);
            try (var walk = Files.walk(project)) {
                List<String> remaining = walk.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".java")).toList();

                assertFalse(remaining.contains("EskiTest.java"),
                        "Bayat test silinmemiş: " + remaining);
                assertTrue(remaining.contains("LoginPage.java"),
                        "Page Object silinmiş — testler derlenemez: " + remaining);
                assertTrue(remaining.contains("DriverFactory.java"),
                        "DriverFactory silinmiş: " + remaining);
            }
        }
    }

    @Nested
    @DisplayName("Maven komutu çözümleme")
    class MavenCommandResolution {

        @Test
        @DisplayName("Projedeki wrapper varsa öncelikli kullanılır")
        void prefersProjectWrapper() throws Exception {
            Path projectDir = Files.createDirectories(tempDir.resolve("proje"));
            Path wrapper = Files.writeString(projectDir.resolve("mvnw"), "#!/bin/sh\n");

            String command = service.resolveMavenCommand(projectDir);

            assertEquals(wrapper.toAbsolutePath().toString(), command);
        }

        @Test
        @DisplayName("Wrapper çalıştırılabilir yapılır")
        void makesWrapperExecutable() throws Exception {
            Path projectDir = Files.createDirectories(tempDir.resolve("proje2"));
            Path wrapper = Files.writeString(projectDir.resolve("mvnw"), "#!/bin/sh\n");

            service.resolveMavenCommand(projectDir);

            assertTrue(wrapper.toFile().canExecute(), "mvnw çalıştırılabilir yapılmamış");
        }

        /**
         * Hiçbir aday bulunamazsa PATH'e düşülmeli — boş veya null dönerse
         * ProcessBuilder anlamsız bir hata ile patlar.
         */
        @Test
        @DisplayName("Aday bulunamazsa PATH'e düşer, boş dönmez")
        void fallsBackToPath() throws Exception {
            Path projectDir = Files.createDirectories(tempDir.resolve("bos-proje"));

            String command = service.resolveMavenCommand(projectDir);

            assertNotNull(command);
            assertFalse(command.isBlank(), "Maven komutu boş döndü");
        }
    }
}
