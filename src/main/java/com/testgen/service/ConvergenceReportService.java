package com.testgen.service;

import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestRunStatus;
import com.testgen.repository.CaseOutcomeView;
import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Sistem öğreniyor mu?" sorusunu ölçümle cevaplar.
 *
 * NEDEN VAR: Öğrenme döngüsü kuruluydu (koşum hatası kaydediliyor, dersler bir sonraki
 * üretimin bağlamına ekleniyor) ama YAKINSADIĞINA DAİR HİÇBİR ÖLÇÜM YOKTU. Otonom test
 * mühendisi iddiasının tek gerçek göstergesi şudur: aynı hedef servis için ardışık
 * üretim turlarında geçme oranı artıyor mu?
 *
 * Rapor iki kırılım verir:
 *  - Tur bazında: her üretim isteğinin senaryo geçme oranı, zaman sırasıyla
 *  - Kaynak bazında: LLM üretimi vs gözlemden üretilen testlerin geçme oranı
 *
 * Hiçbir değer tahmin edilmez; hepsi kaydedilmiş koşum sonuçlarından sayılır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConvergenceReportService {

    private final TestGenerationRequestRepository requestRepository;
    private final GeneratedTestCaseRepository testCaseRepository;

    /** Tek bir üretim turunun ölçümü. */
    public record Round(
            String requestId,
            String framework,
            String createdAt,
            int totalCases,
            int passedCases,
            int totalScenarios,
            int passedScenarios,
            Integer scenarioPassRate,
            int llmCases,
            int observedCases,
            Integer llmPassRate,
            Integer observedPassRate
    ) {}

    /** Bir hedef servisin tüm turları + özet. */
    public record ServiceConvergence(
            String serviceKey,
            int roundCount,
            List<Round> rounds,
            Integer firstRoundPassRate,
            Integer lastRoundPassRate,
            String trend
    ) {}

    /**
     * Hedef servis bazında yakınsama raporu.
     *
     * @param serviceKey null ise tüm servisler döner
     */
    public List<ServiceConvergence> report(String serviceKey) {
        // Tüm koşum sonuçları TEK sorguda çekilir ve istek bazında gruplanır.
        // Önceki sürüm her istek için ayrı sorgu atıyordu (N+1) ve ağır metin
        // sütunlarını da belleğe alıyordu.
        Map<String, List<CaseOutcomeView>> casesByRequest = new java.util.HashMap<>();
        for (CaseOutcomeView view : testCaseRepository.findOutcomeViews()) {
            casesByRequest.computeIfAbsent(view.requestId(), k -> new ArrayList<>()).add(view);
        }

        Map<String, List<TestGenerationRequest>> byService = new LinkedHashMap<>();

        for (TestGenerationRequest request : requestRepository.findAll()) {
            String key = AgentLearningService.serviceKeyOf(request);
            if (serviceKey != null && !serviceKey.equals(key)) {
                continue;
            }
            byService.computeIfAbsent(key, k -> new ArrayList<>()).add(request);
        }

        List<ServiceConvergence> out = new ArrayList<>();
        byService.forEach((key, requests) -> {
            requests.sort(Comparator.comparing(TestGenerationRequest::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            List<Round> rounds = requests.stream()
                    .map(r -> measureRound(r, casesByRequest.getOrDefault(r.getId(), List.of())))
                    // Hiç koşulmamış turlar yakınsama sinyali taşımaz
                    .filter(r -> r.totalScenarios() > 0)
                    .toList();

            if (rounds.isEmpty()) {
                return;
            }
            Integer first = rounds.get(0).scenarioPassRate();
            Integer last = rounds.get(rounds.size() - 1).scenarioPassRate();
            out.add(new ServiceConvergence(key, rounds.size(), rounds, first, last, trend(first, last)));
        });
        return out;
    }

    private Round measureRound(TestGenerationRequest request, List<CaseOutcomeView> cases) {
        int passedCases = 0, totalScenarios = 0, passedScenarios = 0;
        int llmCases = 0, observedCases = 0, llmPassed = 0, observedPassed = 0;

        for (CaseOutcomeView tc : cases) {
            boolean passed = tc.runStatus() == TestRunStatus.PASSED;
            if (passed) {
                passedCases++;
            }
            totalScenarios += nz(tc.totalScenarios());
            passedScenarios += nz(tc.passedScenarios());

            if (tc.deterministic()) {
                observedCases++;
                if (passed) observedPassed++;
            } else {
                llmCases++;
                if (passed) llmPassed++;
            }
        }

        return new Round(
                request.getId(),
                String.valueOf(request.getFramework()),
                request.getCreatedAt() == null ? null : request.getCreatedAt().toString(),
                cases.size(), passedCases, totalScenarios, passedScenarios,
                rate(passedScenarios, totalScenarios),
                llmCases, observedCases,
                rate(llmPassed, llmCases), rate(observedPassed, observedCases));
    }

    /** Yükseliş/düşüş yalnızca en az 5 puanlık fark varsa iddia edilir — gürültüyü trend sanmayalım. */
    private static String trend(Integer first, Integer last) {
        if (first == null || last == null) {
            return "ölçülemedi";
        }
        int delta = last - first;
        if (delta >= 5) return "yükseliyor (+" + delta + " puan)";
        if (delta <= -5) return "düşüyor (" + delta + " puan)";
        return "sabit";
    }

    private static Integer rate(int passed, int total) {
        return total == 0 ? null : Math.round(passed * 100f / total);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
