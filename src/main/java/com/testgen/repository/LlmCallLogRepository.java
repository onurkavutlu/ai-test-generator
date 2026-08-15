package com.testgen.repository;

import com.testgen.model.LlmCallLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, String> {

    List<LlmCallLog> findTop100ByOrderByCalledAtDesc();

    /**
     * En yeni N çağrı. Limit sabit değil Pageable ile verilir; böylece
     * LlmReportStore.MAX_SIZE tek kaynak olarak kalır (findTop500... gibi bir
     * metot adı limiti ikinci bir yere kopyalardı).
     */
    @Query("SELECT l FROM LlmCallLog l ORDER BY l.calledAt DESC")
    List<LlmCallLog> findRecent(Pageable pageable);

    List<LlmCallLog> findByCallTypeOrderByCalledAtDesc(String callType);

    long countBySuccess(boolean success);

    /**
     * Belirli bir zaman penceresindeki çağrılar.
     * Ajan ölçüm koşumu, LLM maliyetini üretime bu pencereyle atfeder — çağrı kayıtlarında
     * requestId alanı yok ve koşumlar sıralı yürütüldüğü için pencere kesin sonuç verir.
     */
    List<LlmCallLog> findByCalledAtBetween(LocalDateTime start, LocalDateTime end);
}
