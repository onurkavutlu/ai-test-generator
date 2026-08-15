package com.testgen.service;

import com.testgen.model.AgentLearning;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.repository.AgentLearningRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentLearningServiceTest {

    @Mock
    private AgentLearningRepository repository;

    @InjectMocks
    private AgentLearningService learningService;

    private final TestGenerationRequest request = TestGenerationRequest.builder()
            .testType(TestType.BACKEND_API)
            .framework(TestFramework.KARATE)
            .swaggerUrl("https://fakerestapi.azurewebsites.net/swagger/v1/swagger.json")
            .build();

    @Test
    public void serviceKeyIsExtractedFromSwaggerUrl() {
        assertEquals("fakerestapi.azurewebsites.net", AgentLearningService.serviceKeyOf(request));
    }

    @Test
    public void serviceKeyFallsBackToApplicationUrlThenRawPayloadThenGlobal() {
        TestGenerationRequest appUrl = TestGenerationRequest.builder()
                .applicationUrl("https://app.example.com/login").build();
        assertEquals("app.example.com", AgentLearningService.serviceKeyOf(appUrl));

        TestGenerationRequest raw = TestGenerationRequest.builder()
                .rawPayload("curl -X GET https://raw.example.com/pets -H 'Accept: json'").build();
        assertEquals("raw.example.com", AgentLearningService.serviceKeyOf(raw));

        assertEquals("global", AgentLearningService.serviceKeyOf(TestGenerationRequest.builder().build()));
    }

    @Test
    public void runFailureIsRecordedWithServiceKey() {
        when(repository.findTop10ByServiceKeyOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        GeneratedTestCase failed = GeneratedTestCase.builder()
                .testName("GetPetTest")
                .runOutput("status code was: 404, expected: 200")
                .build();

        learningService.recordRunFailure(request, failed);

        ArgumentCaptor<AgentLearning> captor = ArgumentCaptor.forClass(AgentLearning.class);
        verify(repository).save(captor.capture());
        AgentLearning saved = captor.getValue();
        assertEquals("fakerestapi.azurewebsites.net", saved.getServiceKey());
        assertEquals(AgentLearning.Source.RUN_FAILURE, saved.getSource());
        assertTrue(saved.getLesson().contains("GetPetTest"));
        assertTrue(saved.getLesson().contains("404"));
    }

    @Test
    public void duplicateLessonIsNotSavedTwice() {
        GeneratedTestCase failed = GeneratedTestCase.builder()
                .testName("GetPetTest")
                .runOutput("status code was: 404, expected: 200")
                .build();

        // İlk kayıt: depo boş → kaydedilir
        when(repository.findTop10ByServiceKeyOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        learningService.recordRunFailure(request, failed);

        ArgumentCaptor<AgentLearning> captor = ArgumentCaptor.forClass(AgentLearning.class);
        verify(repository).save(captor.capture());

        // İkinci kayıt: aynı ders depoda → atlanır
        when(repository.findTop10ByServiceKeyOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(captor.getValue()));
        learningService.recordRunFailure(request, failed);

        verify(repository, times(1)).save(any());
    }

    @Test
    public void learningsAreInjectedIntoContext() {
        when(repository.findTop10ByServiceKeyOrderByCreatedAtDesc("fakerestapi.azurewebsites.net"))
                .thenReturn(List.of(AgentLearning.builder()
                        .serviceKey("fakerestapi.azurewebsites.net")
                        .source(AgentLearning.Source.SELF_HEAL)
                        .lesson("Test 'GetPetTest' LLM düzeltmesi gerektirdi: /pet/{id} 404 dönüyor")
                        .build()));

        request.setAdditionalContext("mevcut bağlam");
        String enriched = learningService.enrichWithLearnings(request);

        assertTrue(enriched.startsWith("mevcut bağlam"));
        assertTrue(enriched.contains(AgentLearningService.SECTION_TITLE));
        assertTrue(enriched.contains("GetPetTest"));
        assertTrue(enriched.contains("[SELF_HEAL]"));
    }

    @Test
    public void enrichmentIsIdempotentAndSkipsWhenNoLearnings() {
        // Ders yoksa bağlam değişmez
        when(repository.findTop10ByServiceKeyOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        request.setAdditionalContext("değişmemeli");
        assertEquals("değişmemeli", learningService.enrichWithLearnings(request));

        // Bölüm zaten varsa tekrar eklenmez (repo'ya hiç gidilmez)
        request.setAdditionalContext("x\n\n" + AgentLearningService.SECTION_TITLE + "\nvar olan");
        String result = learningService.enrichWithLearnings(request);
        assertEquals(request.getAdditionalContext(), result);
    }

    @Test
    public void selfHealLessonMentionsVersionAndWarning() {
        when(repository.findTop10ByServiceKeyOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        GeneratedTestCase failed = GeneratedTestCase.builder()
                .testName("CreatePetTest")
                .runOutput("match failed: response.name")
                .build();

        learningService.recordSelfHeal(request, failed, 2);

        ArgumentCaptor<AgentLearning> captor = ArgumentCaptor.forClass(AgentLearning.class);
        verify(repository).save(captor.capture());
        assertEquals(AgentLearning.Source.SELF_HEAL, captor.getValue().getSource());
        assertTrue(captor.getValue().getLesson().contains("v2"));
        assertTrue(captor.getValue().getLesson().contains("aynı varsayımı tekrarlama"));
    }
}
