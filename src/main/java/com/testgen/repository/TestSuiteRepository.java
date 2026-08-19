package com.testgen.repository;

import com.testgen.model.TestSuite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestSuiteRepository extends JpaRepository<TestSuite, String> {

    List<TestSuite> findAllByOrderByCreatedAtDesc();

    /**
     * Verilen isteklerden hangilerinin case'i bir suite'e bağlı.
     *
     * <p>Saklama temizliği bu istekleri atlar: {@code suite_test_cases} bağlantı satırı
     * case'e yabancı anahtarla bağlıdır; silinseydi ya kısıt ihlali alınır ya da
     * kullanıcının elle kurduğu suite sessizce eksilirdi.
     */
    @Query("SELECT DISTINCT tc.request.id FROM TestSuite s JOIN s.testCases tc WHERE tc.request.id IN :ids")
    List<String> findRequestIdsLinkedToSuites(@Param("ids") List<String> ids);
}
