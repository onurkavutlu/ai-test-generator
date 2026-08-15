package com.testgen.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt şablonları — 460 satırlık, hiç testi olmayan sınıf.
 *
 * <p><b>Neden test ediliyor:</b> Prompt'un uzunluğu doğrudan maliyettir. Sınıfın kendi
 * yorumuna göre bağlam 25.000 karaktere büyüyüp çağrı başına ~40 saniyeye çıkmış ve
 * 5 case'lik bir modül 8-10 dakika sürmüş. {@code boundContext} bu sorunun çözümü —
 * ama sessizce bozulabilir: kesme mantığı yanlış çalışırsa ya bütçe aşılır (yavaşlar)
 * ya da GÖZLEM bölümü kesilir (üretim kalitesi düşer, kimse fark etmez).
 *
 * <p>Kesme sırası bilinçlidir: {@code ## OBSERVED} hedeften canlı okunan gerçek veridir
 * ve korunmalıdır; kısaltma ajan analizlerinden yapılır. Bu öncelik burada kilitleniyor.
 */
class PromptTemplatesTest {

    private static final int CONTEXT_BUDGET = 6000;

    private String repeat(String seed, int length) {
        return seed.repeat((length / seed.length()) + 1).substring(0, length);
    }

    @Nested
    @DisplayName("Bağlam bütçesi (boundContext)")
    class ContextBudget {

        @Test
        @DisplayName("Bütçe altındaki bağlam olduğu gibi korunur")
        void shortContextIsUntouched() {
            String context = "Kısa bağlam metni";
            assertEquals(context, PromptTemplates.boundContext(context));
        }

        @Test
        @DisplayName("null bağlam boş metne dönüşür, NPE fırlatmaz")
        void nullContextBecomesEmptyString() {
            assertEquals("", PromptTemplates.boundContext(null));
        }

        @Test
        @DisplayName("Tam bütçe sınırındaki bağlam kısaltılmaz")
        void contextExactlyAtBudgetIsUntouched() {
            String context = repeat("a", CONTEXT_BUDGET);
            assertEquals(context, PromptTemplates.boundContext(context));
        }

        /**
         * Gözlem bölümü yoksa kesecek "korunacak" bir şey de yok; baştan kesilir ve
         * kesildiği açıkça işaretlenir — model, bağlamın eksik olduğunu bilmeli.
         */
        @Test
        @DisplayName("Gözlem bölümü yokken baştan kesilir ve kesildiği işaretlenir")
        void withoutObservedSectionTruncatesFromStart() {
            String context = repeat("ajan analizi. ", 20_000);

            String bounded = PromptTemplates.boundContext(context);

            assertTrue(bounded.startsWith("ajan analizi."), bounded.substring(0, 40));
            assertTrue(bounded.contains("[bağlam kısaltıldı]"), "Kesme işareti eksik");
            assertTrue(bounded.length() < context.length());
        }

        /**
         * En kritik davranış: gözlem verisi hedeften CANLI okunmuştur ve üretim
         * kalitesini en çok belirleyen kısımdır. Kısaltma ajan analizinden yapılmalı,
         * gözlemden değil.
         */
        @Test
        @DisplayName("Gözlem bölümü korunur, kısaltma ajan analizinden yapılır")
        void observedSectionSurvivesAgentAnalysisIsTrimmed() {
            String agents = repeat("AJAN ANALIZI. ", 20_000);
            String observed = "## OBSERVED\nStatus: 200\nBody: {\"id\":7,\"name\":\"Pamuk\"}";
            String context = agents + observed;

            String bounded = PromptTemplates.boundContext(context);

            assertTrue(bounded.contains("## OBSERVED"), "Gözlem başlığı kaybolmuş");
            assertTrue(bounded.contains("Pamuk"), "Gözlenen gövde kaybolmuş: " + bounded);
            assertTrue(bounded.contains("Status: 200"), "Gözlenen status kaybolmuş");
            assertTrue(bounded.contains("[ajan analizi kısaltıldı]"),
                    "Kısaltmanın ajan analizinden yapıldığı işaretlenmeli");
        }

        @Test
        @DisplayName("Kısaltılan bağlam bütçeyi aşmaz")
        void boundedContextStaysWithinBudget() {
            String agents = repeat("AJAN. ", 30_000);
            String observed = "## OBSERVED\n" + repeat("gozlem ", 1_000);

            String bounded = PromptTemplates.boundContext(agents + observed);

            // Kesme işaretleri küçük bir pay ekler; asıl kural bağlamın ONDA BİRİNE
            // inmesi değil, 30.000+ karakterin bütçe mertebesine çekilmesidir.
            assertTrue(bounded.length() < CONTEXT_BUDGET + 200,
                    "Bütçe aşıldı: " + bounded.length());
        }

        /**
         * Gözlem tek başına bütçeyi dolduruyorsa ajan analizine hiç yer kalmaz —
         * yalnızca gözlem gönderilir. Aksi hâlde bütçe sessizce aşılırdı.
         */
        @Test
        @DisplayName("Gözlem tek başına bütçeyi doldurursa yalnızca gözlem gönderilir")
        void hugeObservedSectionCrowdsOutAgentAnalysis() {
            String agents = repeat("AJAN. ", 5_000);
            String observed = "## OBSERVED\n" + repeat("gozlem verisi ", 20_000);

            String bounded = PromptTemplates.boundContext(agents + observed);

            assertTrue(bounded.startsWith("## OBSERVED"),
                    "Bütçe dolduğunda gözlemle başlamalı: " + bounded.substring(0, 40));
            assertFalse(bounded.contains("AJAN."), "Ajan analizi tamamen düşmeliydi");
            assertTrue(bounded.contains("[gözlem kısaltıldı]"));
            assertTrue(bounded.length() < CONTEXT_BUDGET + 200);
        }
    }

    @Nested
    @DisplayName("Karate prompt'u")
    class KaratePrompt {

        @Test
        @DisplayName("Endpoint, method ve ISTQB kuralları prompt'a girer")
        void carriesEndpointMethodAndIstqbRules() {
            String prompt = PromptTemplates.buildKaratePrompt(
                    "openapi: 3.0.0", "/api/pets/{id}", "GET", "bağlam");

            assertTrue(prompt.contains("/api/pets/{id}"), "Endpoint eksik");
            assertTrue(prompt.contains("GET"), "Method eksik");
            assertTrue(prompt.contains("ISTQB"), "ISTQB kural seti eksik");
            assertTrue(prompt.contains("[BVA]"), "Sınır değer tekniği eksik");
            assertTrue(prompt.contains("[EP]"), "Denklik sınıfı tekniği eksik");
        }

        /**
         * ISTQB metadata formatı sınıflandırmanın tek kaynağı: TestCaseClassifier
         * kategori/teknik bilgisini senaryo başlığındaki etiketlerden okuyor. Format
         * prompt'tan düşerse etiket üretilmez ve sınıflandırma sessizce boşa düşer.
         */
        @Test
        @DisplayName("Senaryo başlık formatı prompt'ta tarif edilir")
        void describesScenarioTitleFormat() {
            String prompt = PromptTemplates.buildKaratePrompt("spec", "/x", "GET", "");

            assertTrue(prompt.contains("[CATEGORY][PRIORITY][TECHNIQUE]"),
                    "Başlık formatı tarif edilmemiş — sınıflandırma etiketsiz kalır");
        }

        @Test
        @DisplayName("Aşırı uzun bağlam prompt'a sığdırılır")
        void longContextIsBounded() {
            String huge = repeat("bağlam. ", 40_000);

            String prompt = PromptTemplates.buildKaratePrompt("spec", "/x", "GET", huge);

            assertTrue(prompt.length() < huge.length(),
                    "Bağlam kısaltılmamış — prompt maliyeti kontrolsüz büyür");
        }

        /**
         * null bağlam prompt'a "null" metni olarak SIZMAMALI — model bunu gerçek bir
         * bağlam sanıp yorumlar. Kontrol dar tutuluyor: prompt şablonu zaten
         * {@code '#notnull'} ifadesini içeriyor, düz {@code contains("null")} yanlış
         * eşleşir.
         */
        @Test
        @DisplayName("null bağlam prompt'a 'null' metni olarak sızmaz")
        void nullContextDoesNotLeakIntoPrompt() {
            String prompt = PromptTemplates.buildKaratePrompt("", "/x", "GET", null);

            assertTrue(prompt.contains("/x"));
            assertFalse(prompt.contains("Context: null"),
                    "null bağlam prompt'a sızmış");
        }
    }

    @Nested
    @DisplayName("Selenium prompt'u")
    class SeleniumPrompt {

        @Test
        @DisplayName("Sayfa adresi ve kullanıcı hikayesi prompt'a girer")
        void carriesPageUrlAndUserStory() {
            String prompt = PromptTemplates.buildSeleniumPrompt(
                    "http://localhost:8080/login", "Kullanıcı giriş yapabilmeli", "<input id='user'>");

            assertTrue(prompt.contains("http://localhost:8080/login"));
            assertTrue(prompt.contains("Kullanıcı giriş yapabilmeli"));
        }

        /**
         * HTML ipucu, gerçek sayfadan okunan selector'ları taşır. Prompt'a girmezse
         * model selector uydurur ve testler ilk koşumda patlar.
         */
        @Test
        @DisplayName("Gözlenen HTML ipucu prompt'a girer — selector uydurma engellenir")
        void htmlHintReachesPrompt() {
            String prompt = PromptTemplates.buildSeleniumPrompt(
                    "http://x/login", "hikaye", "<input id='username'><button id='submit'>");

            assertTrue(prompt.contains("username"), "Gözlenen selector prompt'a girmemiş");
            assertTrue(prompt.contains("submit"));
        }
    }

    @Nested
    @DisplayName("Diğer prompt kurucular")
    class OtherBuilders {

        @Test
        @DisplayName("REST Assured prompt'u endpoint ve method taşır")
        void restAssuredCarriesEndpointAndMethod() {
            String prompt = PromptTemplates.buildRestAssuredPrompt(
                    "spec", "/api/orders", "POST", "bağlam");

            assertTrue(prompt.contains("/api/orders"));
            assertTrue(prompt.contains("POST"));
        }

        @Test
        @DisplayName("Yakalanmış yanıt prompt'u ham yükü taşır")
        void capturedResponseCarriesRawPayload() {
            String prompt = PromptTemplates.buildCapturedResponsePrompt(
                    "curl -X GET 'http://x/api/pets'", "bağlam");

            assertTrue(prompt.contains("curl -X GET"));
        }

        @Test
        @DisplayName("GraphQL prompt'u sorgu detaylarını taşır")
        void graphqlCarriesQueryDetails() {
            String prompt = PromptTemplates.buildGraphQLPrompt("query { pets { id } }", "bağlam");

            assertTrue(prompt.contains("query { pets { id } }"));
        }

        @Test
        @DisplayName("SOAP prompt'u XML zarfını taşır")
        void soapCarriesEnvelope() {
            String prompt = PromptTemplates.buildSoapPrompt(
                    "<soap:Envelope><soap:Body/></soap:Envelope>", "bağlam");

            assertTrue(prompt.contains("soap:Envelope"));
        }

        @Test
        @DisplayName("Kullanıcı hikayesi prompt'u framework adını taşır")
        void userStoryCarriesFramework() {
            String prompt = PromptTemplates.buildUserStoryPrompt(
                    "Kullanıcı sipariş verebilmeli", "KARATE", "bağlam");

            assertTrue(prompt.contains("Kullanıcı sipariş verebilmeli"));
            assertTrue(prompt.contains("KARATE"));
        }

        @Test
        @DisplayName("Ham yük prompt'u yük tipini taşır")
        void rawPayloadCarriesPayloadType() {
            String prompt = PromptTemplates.buildRawPayloadPrompt(
                    "{\"a\":1}", "HAR", "bağlam");

            assertTrue(prompt.contains("HAR"));
        }

        /**
         * Tüm prompt kurucular ISTQB kural setini eklemeli — biri unutulursa o girdi
         * tipinde üretilen testler negatif/sınır senaryosu içermez ve bunu kimse
         * fark etmez.
         */
        @Test
        @DisplayName("Tüm prompt kurucular ISTQB kural setini içerir")
        void allBuildersIncludeIstqbRules() {
            assertTrue(PromptTemplates.buildKaratePrompt("s", "/x", "GET", "").contains("ISTQB"));
            assertTrue(PromptTemplates.buildRestAssuredPrompt("s", "/x", "GET", "").contains("ISTQB"));
            assertTrue(PromptTemplates.buildSeleniumPrompt("u", "h", "").contains("ISTQB"));
            assertTrue(PromptTemplates.buildGraphQLPrompt("q", "").contains("ISTQB"));
            assertTrue(PromptTemplates.buildSoapPrompt("x", "").contains("ISTQB"));
            assertTrue(PromptTemplates.buildUserStoryPrompt("h", "KARATE", "").contains("ISTQB"));
        }
    }
}
