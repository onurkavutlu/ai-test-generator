package com.testgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testgen.llm.LlmService;
import com.testgen.model.GeneratedTestCase;
import com.testgen.model.MockResponse;
import com.testgen.model.TestFramework;
import com.testgen.repository.MockResponseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MockDataGenerationServiceTest {

    @Mock
    private LlmService llmService;

    @Mock
    private MockResponseRepository mockResponseRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MockDataGenerationService mockDataGenerationService;

    @Test
    public void testGenerateMockDataNonKarateIgnored() {
        GeneratedTestCase tc = GeneratedTestCase.builder()
                .framework(TestFramework.SELENIUM)
                .build();

        mockDataGenerationService.generateMockDataForTestCase(tc);

        verifyNoInteractions(llmService, mockResponseRepository);
    }

    @Test
    public void testGenerateMockDataKarateSuccess() {
        GeneratedTestCase tc = GeneratedTestCase.builder()
                .framework(TestFramework.KARATE)
                .testName("GetPetTest")
                .testContent("Feature: Get pet\nScenario: Get pet detail\nGiven url 'https://api.com'\nWhen method GET")
                .build();

        String llmJsonResponse = """
                ```json
                [
                  {
                    "path": "/pet/10",
                    "method": "GET",
                    "statusCode": 200,
                    "responseBody": "{\\"id\\":10,\\"name\\":\\"Mavi\\"}"
                  }
                ]
                ```
                """;

        when(llmService.generateTestCase(anyString())).thenReturn(llmJsonResponse);
        when(mockResponseRepository.findByPathAndMethod("/pet/10", "GET")).thenReturn(Optional.empty());

        mockDataGenerationService.generateMockDataForTestCase(tc);

        ArgumentCaptor<MockResponse> captor = ArgumentCaptor.forClass(MockResponse.class);
        verify(mockResponseRepository, times(1)).save(captor.capture());

        MockResponse saved = captor.getValue();
        assertEquals("/pet/10", saved.getPath());
        assertEquals("GET", saved.getMethod());
        assertEquals(200, saved.getStatusCode());
        assertEquals("{\"id\":10,\"name\":\"Mavi\"}", saved.getResponseBody());
    }

    @Test
    public void testRedirectKarateUrl() {
        String originalFeatureContent = """
                Feature: Get pet
                  Background:
                    * url 'https://petstore3.swagger.io/api/v3'
                    * header Accept = 'application/json'
                  Scenario: Get detail
                    Given url "https://petstore3.swagger.io/api/v3"
                    And path 'pet', 10
                """;

        String redirected = mockDataGenerationService.redirectKarateUrl(originalFeatureContent);

        assertNotNull(redirected);
        assertEquals(true, redirected.contains("* url 'http://localhost:8080/api/v1/mock'"));
        assertEquals(false, redirected.contains("https://petstore3.swagger.io"));
    }
}
