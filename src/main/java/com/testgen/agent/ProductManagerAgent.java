package com.testgen.agent;

import com.testgen.llm.LlmService;
import org.springframework.stereotype.Component;

@Component
public class ProductManagerAgent extends AbstractLlmAgent {

    public ProductManagerAgent(LlmService llmService) {
        super(llmService);
    }

    @Override
    public AiAgentRole role() {
        return AiAgentRole.PRODUCT_MANAGER;
    }

    @Override
    public AiAgentResult analyze(AiAgentContext context) {
        return runAgent(context, "Product Manager Agent",
                "Acceptance criteria, business risk, müşteri etkisi ve öncelik çıkar.",
                """
                Acceptance criteria kritik kullanıcı akışını, hata mesajlarını ve başarı durumunu kapsamalı.
                Business risk: ödeme, login, veri bütünlüğü veya müşteri deneyimi etkisi netleştirilmeli.
                Definition of Done: testler koşulmalı, sonuçlar raporlanmalı ve başarısızlıklar iyileştirme adayına dönüşmeli.
                """);
    }
}
