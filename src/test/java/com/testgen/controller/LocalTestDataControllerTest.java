package com.testgen.controller;

import com.testgen.repository.GeneratedTestCaseRepository;
import com.testgen.repository.TestGenerationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb_local;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "test-generator.seeding.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
public class LocalTestDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestGenerationRequestRepository requestRepository;

    @Autowired
    private GeneratedTestCaseRepository testCaseRepository;

    @Test
    public void testSeedAndClearData() throws Exception {
        // 1. Seed endpoint'ini çağır ve kontrol et
        mockMvc.perform(post("/api/v1/dev/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Local seed data olusturuldu")))
                .andExpect(jsonPath("$.requestCount", greaterThan(0)))
                .andExpect(jsonPath("$.testCaseCount", greaterThan(0)));

        // Veritabanının gerçekten dolduğunu doğrula
        long requestCount = requestRepository.count();
        long testCaseCount = testCaseRepository.count();
        assertEquals(7, requestCount);
        assertEquals(13, testCaseCount);

        // 2. Clear endpoint'ini çağır ve kontrol et
        mockMvc.perform(delete("/api/v1/dev/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Local seed data temizlendi")));

        // Veritabanının temizlendiğini doğrula
        assertEquals(0, requestRepository.count());
        assertEquals(0, testCaseRepository.count());
    }
}
