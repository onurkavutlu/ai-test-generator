package com.testgen.controller;

import com.testgen.model.MockResponse;
import com.testgen.repository.MockResponseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb_mock;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "test-generator.seeding.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
public class MockServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockResponseRepository mockResponseRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        mockResponseRepository.deleteAll();
    }

    @Test
    public void testMockRegistrationAndInterception() throws Exception {
        // 1. Register a GET mock response
        MockResponse mockGet = MockResponse.builder()
                .path("/pet/10")
                .method("GET")
                .statusCode(200)
                .responseBody("{\"id\":10,\"name\":\"Mavi\"}")
                .build();

        mockMvc.perform(post("/api/v1/mock-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mockGet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path", is("/pet/10")))
                .andExpect(jsonPath("$.method", is("GET")));

        // 2. Intercept and fetch the registered mock
        mockMvc.perform(get("/api/v1/mock/pet/10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(10)))
                .andExpect(jsonPath("$.name", is("Mavi")));

        // 3. Register a POST mock response
        MockResponse mockPost = MockResponse.builder()
                .path("/pet")
                .method("POST")
                .statusCode(201)
                .responseBody("{\"message\":\"created\"}")
                .build();

        mockMvc.perform(post("/api/v1/mock-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mockPost)))
                .andExpect(status().isOk());

        // 4. Intercept the POST mock
        mockMvc.perform(post("/api/v1/mock/pet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mavi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message", is("created")));

        // 5. Test fallback / 404 response
        mockMvc.perform(get("/api/v1/mock/unknown-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Mock response not found")));

        // 6. List all configs
        mockMvc.perform(get("/api/v1/mock-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // 7. Delete one config
        MockResponse saved = mockResponseRepository.findByPathAndMethod("/pet/10", "GET").orElseThrow();
        mockMvc.perform(delete("/api/v1/mock-configs/" + saved.getId()))
                .andExpect(status().isOk());

        // Verify only 1 remains
        mockMvc.perform(get("/api/v1/mock-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // 8. Clear all
        mockMvc.perform(delete("/api/v1/mock-configs"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/mock-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
