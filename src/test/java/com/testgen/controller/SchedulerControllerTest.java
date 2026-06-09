package com.testgen.controller;

import com.testgen.model.RequestStatus;
import com.testgen.model.TestFramework;
import com.testgen.model.TestGenerationRequest;
import com.testgen.model.TestType;
import com.testgen.repository.TestGenerationRequestRepository;
import com.testgen.scheduler.DailySchedulerService;
import com.testgen.scheduler.SchedulerRunSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb_sched;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "test-generator.seeding.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
public class SchedulerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestGenerationRequestRepository requestRepository;

    @MockitoBean
    private DailySchedulerService schedulerService;

    @Test
    public void testEnable() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .scheduledRun(false)
                .build();

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(requestRepository.save(any(TestGenerationRequest.class))).thenReturn(request);

        mockMvc.perform(post("/api/v1/scheduler/req-123/enable")
                        .param("autoGenerate", "true")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.scheduledRun").value(true))
                .andExpect(jsonPath("$.autoGenerate").value(true));

        verify(requestRepository, times(1)).save(request);
    }

    @Test
    public void testDisable() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .scheduledRun(true)
                .build();

        when(requestRepository.findById("req-123")).thenReturn(Optional.of(request));
        when(requestRepository.save(any(TestGenerationRequest.class))).thenReturn(request);

        mockMvc.perform(post("/api/v1/scheduler/req-123/disable")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.scheduledRun").value(false));

        verify(requestRepository, times(1)).save(request);
    }

    @Test
    public void testTriggerNow() throws Exception {
        SchedulerRunSummary summary = new SchedulerRunSummary(
                "req-123",
                5,
                3,
                2,
                1,
                LocalDateTime.now()
        );

        when(schedulerService.triggerManually("req-123")).thenReturn(summary);

        mockMvc.perform(post("/api/v1/scheduler/req-123/trigger-now")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.totalExisting").value(5))
                .andExpect(jsonPath("$.passed").value(3))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.newGenerated").value(1));

        verify(schedulerService, times(1)).triggerManually("req-123");
    }

    @Test
    public void testListScheduled() throws Exception {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .id("req-123")
                .testType(TestType.BACKEND_API)
                .framework(TestFramework.KARATE)
                .status(RequestStatus.GENERATED)
                .scheduledRun(true)
                .autoGenerateOnFailure(true)
                .build();

        when(requestRepository.findAllScheduled()).thenReturn(List.of(request));

        mockMvc.perform(get("/api/v1/scheduler/list")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("req-123"))
                .andExpect(jsonPath("$[0].testType").value("BACKEND_API"))
                .andExpect(jsonPath("$[0].framework").value("KARATE"));
    }
}
