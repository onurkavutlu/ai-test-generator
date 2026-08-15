package com.testgen.controller;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Web katmanı testleri için ortak kurulum.
 *
 * <p><b>Neden meta-annotation:</b> Spring test bağlamını, üzerindeki yapılandırmanın
 * tamamı (profil, property'ler, mock'lanan bean kümesi) anahtar olacak şekilde önbelleğe
 * alır. Her test sınıfı kendi {@code spring.datasource.url}'ini yazarsa her sınıf için
 * AYRI bağlam ayağa kalkar — 9 controller testi 9 kez Spring başlatması demektir.
 * Aynı property kümesi paylaşıldığında bağlam bir kez kurulup yeniden kullanılır.
 *
 * <p>Not: {@code @MockitoBean} alanları da bağlam anahtarının parçasıdır; aynı bean
 * kümesini mock'layan sınıflar bağlamı paylaşır, farklı olanlar kendi bağlamını alır.
 * Bu kaçınılmaz — buradaki kazanç, gereksiz property farklarını ortadan kaldırmak.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testgen_web;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "notification.email.enabled=false",
        "scheduler.daily-run.cron=-"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
public @interface WebLayerTest {
}
