package com.testgen.service;

import java.util.List;
import java.util.Optional;

/**
 * JavaScript çalıştıktan sonra oluşan sayfa durumunun, yalnız test üretiminde
 * kullanılacak küçük ve kişisel veri içermeyen özeti.
 */
public interface RenderedPageInspector {

    Optional<RenderedPageObservation> inspect(String url);

    /**
     * Kullanıcının açıkça tarif ettiği, yan etkisiz UI yolunu tarayıcıda doğrular.
     * Varsayılan boş sonuçtur: mevcut salt-okunur sayfa gözlemcileri geriye dönük
     * olarak aynı davranmaya devam eder.
     */
    default Optional<UserFlowObservation> inspectUserFlow(String url, String userStory) {
        return Optional.empty();
    }

    record RenderedPageObservation(String title, String finalUrl, List<UiElement> elements) { }

    record UiElement(String tag, String locatorKind, String locatorValue, String label,
                     String type, boolean required) { }

    record UserFlowObservation(String finalUrl, String finalTitle, List<FlowStep> steps,
                               List<String> visibleFacts) { }

    record FlowStep(int number, String action, String locator, String result) { }
}
