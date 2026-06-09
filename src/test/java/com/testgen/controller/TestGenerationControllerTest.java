package com.testgen.controller;

import com.testgen.model.*;
import com.testgen.runner.TestRunnerService;
import com.testgen.service.TestGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb_gen;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "test-generator.seeding.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
public class TestGenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TestGenerationService testGenerationService;

    @MockitoBean
    private TestRunnerService testRunnerService;

    @Test
    public void testGenerateSuccess() throws Exception {
        TestGenerationRequestDto dto = new TestGenerationRequestDto(
                TestType.BACKEND_API,
                TestFramework.KARATE,
                "User story for API test",
                "https://petstore3.swagger.io/api/v3/openapi.json",
                null,
                null,
                "API context"
        );

        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.PENDING)
                .build();

        when(testGenerationService.createRequest(any(TestGenerationRequest.class))).thenReturn(request);
        when(testGenerationService.generateTests("req-123")).thenReturn(CompletableFuture.completedFuture(List.of()));

        mockMvc.perform(post("/api/v1/tests/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("recipients", "qa@test.com")
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.autoRun").value(true))
                .andExpect(jsonPath("$.message").exists());

        verify(testGenerationService, times(1)).createRequest(any(TestGenerationRequest.class));
        verify(testGenerationService, times(1)).generateTests("req-123");
        verify(testRunnerService, times(1)).runAllForRequest(eq("req-123"), eq(List.of("qa@test.com")));
    }

    @Test
    public void testGenerateWithoutAutoRun() throws Exception {
        TestGenerationRequestDto dto = new TestGenerationRequestDto(
                TestType.BACKEND_API,
                TestFramework.KARATE,
                "User story for API test",
                "https://petstore3.swagger.io/api/v3/openapi.json",
                null,
                null,
                "API context"
        );

        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.PENDING)
                .build();

        when(testGenerationService.createRequest(any(TestGenerationRequest.class))).thenReturn(request);
        when(testGenerationService.generateTests("req-123")).thenReturn(CompletableFuture.completedFuture(List.of()));

        mockMvc.perform(post("/api/v1/tests/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("autoRun", "false")
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.autoRun").value(false));

        verify(testGenerationService, times(1)).generateTests("req-123");
        verify(testRunnerService, never()).runAllForRequest(eq("req-123"), any());
    }

    @Test
    public void testGenerateIncompatibleFramework() throws Exception {
        // BACKEND_API with SELENIUM is incompatible (valid pairings: BACKEND_API/KARATE, FRONTEND_WEB/SELENIUM, MOBILE/APPIUM)
        TestGenerationRequestDto dto = new TestGenerationRequestDto(
                TestType.BACKEND_API,
                TestFramework.SELENIUM,
                "User story",
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/tests/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("testType/framework uyumsuz")));

        verifyNoInteractions(testGenerationService);
    }

    @Test
    public void testGetRequest() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.GENERATED)
                .build();

        when(testGenerationService.getRequest("req-123")).thenReturn(request);

        mockMvc.perform(get("/api/v1/tests/req-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("req-123"))
                .andExpect(jsonPath("$.status").value("GENERATED"));
    }

    @Test
    public void testGetTestCases() throws Exception {
        GeneratedTestCase testCase = GeneratedTestCase.builder()
                .id("case-456")
                .testName("GetPetTest")
                .fileName("GetPetTest.feature")
                .testContent("feature code")
                .framework(TestFramework.KARATE)
                .runStatus(TestRunStatus.PASSED)
                .build();

        when(testGenerationService.getTestCasesByRequestId("req-123")).thenReturn(List.of(testCase));

        mockMvc.perform(get("/api/v1/tests/req-123/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("case-456"))
                .andExpect(jsonPath("$[0].testName").value("GetPetTest"))
                .andExpect(jsonPath("$[0].runStatus").value("PASSED"));
    }

    @Test
    public void testAddManualTestCase() throws Exception {
        GeneratedTestCase saved = GeneratedTestCase.builder()
                .id("case-manual")
                .testName("ManualPaymentAuthorizationTest")
                .fileName("ManualPaymentAuthorizationTest.feature")
                .testContent("Feature: manual")
                .testSummary("[MANUAL] Manuel test")
                .framework(TestFramework.KARATE)
                .runStatus(TestRunStatus.NOT_RUN)
                .build();

        when(testGenerationService.addManualTestCase(eq("req-123"), any(GeneratedTestCase.class))).thenReturn(saved);

        String body = """
                {
                  "testName": "ManualPaymentAuthorizationTest",
                  "testContent": "Feature: manual",
                  "testSummary": "Manuel test"
                }
                """;

        mockMvc.perform(post("/api/v1/tests/req-123/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("case-manual"))
                .andExpect(jsonPath("$.testName").value("ManualPaymentAuthorizationTest"))
                .andExpect(jsonPath("$.generationSource").value("UNKNOWN"))
                .andExpect(jsonPath("$.runStatus").value("NOT_RUN"));

        verify(testGenerationService, times(1)).addManualTestCase(eq("req-123"), any(GeneratedTestCase.class));
    }

    @Test
    public void testRunSingleTest() throws Exception {
        mockMvc.perform(post("/api/v1/tests/cases/case-456/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.testCaseId").value("case-456"))
                .andExpect(jsonPath("$.message").value("Test çalıştırması başlatıldı."));

        verify(testRunnerService, times(1)).runTest("case-456");
    }

    @Test
    public void testRunAll() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .build();

        when(testGenerationService.getRequest("req-123")).thenReturn(request);

        mockMvc.perform(post("/api/v1/tests/req-123/run-all")
                        .param("recipients", "user@test.com,admin@test.com"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.message").exists());

        verify(testRunnerService, times(1)).runAllForRequest(eq("req-123"), eq(List.of("user@test.com", "admin@test.com")));
    }

    @Test
    public void testListAll() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.GENERATED)
                .build();

        when(testGenerationService.getAllRequests()).thenReturn(List.of(request));

        mockMvc.perform(get("/api/v1/tests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("req-123"));
    }

    @Test
    public void testHealth() throws Exception {
        mockMvc.perform(get("/api/v1/tests/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("AI Test Generator"));
    }
}
