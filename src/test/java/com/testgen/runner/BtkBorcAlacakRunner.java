package com.testgen.runner;

import com.intuit.karate.junit5.Karate;

/**
 * BTK Borç/Alacak abonelik sorgulama testleri.
 *
 * <p><b>Sınıf adı bilinçli olarak {@code *Test} ile bitmiyor.</b> Surefire varsayılan
 * olarak yalnızca {@code *Test} / {@code Test*} / {@code *Tests} desenlerini koşar;
 * bu sınıf o desenlerin dışında kaldığı için normal {@code mvn test} koşumuna
 * girmez. Servis VPN arkasında olduğundan iç ağa erişimi olmayan makinelerde ve
 * CI'da testin kırmızı yanmasını istemiyoruz — aynı yaklaşım
 * {@link RegressionRunner} sınıfında da kullanılıyor.
 *
 * <p>Elle koşum:
 * <pre>
 *   ./mvnw test -Dtest=BtkBorcAlacakRunner
 *   ./mvnw test -Dtest=BtkBorcAlacakRunner -Dkarate.options="--tags @smoke"
 *   ./mvnw test -Dtest=BtkBorcAlacakRunner -Dbtk.baseUrl=https://baska-ortam
 * </pre>
 *
 * <p>Yapılandırma {@code src/test/resources/karate-config.js} içinden gelir;
 * her değer {@code -D} özelliği veya ortam değişkeni ile ezilebilir.
 */
public class BtkBorcAlacakRunner {

    @Karate.Test
    Karate aboneliksorgulama() {
        return Karate.run("classpath:btk/btk-abonelik-sorgulama.feature");
    }
}
