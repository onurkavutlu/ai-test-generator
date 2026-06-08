package com.testgen.controller;

import com.testgen.model.MockResponse;
import com.testgen.repository.MockResponseRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/mock")
@RequiredArgsConstructor
public class MockServiceController {

    private final MockResponseRepository mockResponseRepository;

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<String> handleMockRequest(HttpServletRequest request, 
                                                    @RequestBody(required = false) String requestBody) {
        String fullPath = request.getRequestURI();
        String mockPath = fullPath.substring("/api/v1/mock".length());
        if (mockPath.isEmpty()) {
            mockPath = "/";
        }
        
        String method = request.getMethod().toUpperCase();
        log.info("Intercepted mock request: {} {}", method, mockPath);

        // Try exact match
        var responseOpt = mockResponseRepository.findByPathAndMethod(mockPath, method);
        
        // Try match without trailing slash
        if (responseOpt.isEmpty() && mockPath.endsWith("/") && mockPath.length() > 1) {
            String cleanPath = mockPath.substring(0, mockPath.length() - 1);
            responseOpt = mockResponseRepository.findByPathAndMethod(cleanPath, method);
        }

        if (responseOpt.isPresent()) {
            MockResponse mock = responseOpt.get();
            return ResponseEntity.status(HttpStatus.valueOf(mock.getStatusCode()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mock.getResponseBody());
        }

        log.warn("No mock response registered for: {} {}", method, mockPath);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\": \"Mock response not found\", \"path\": \"" + mockPath + "\", \"method\": \"" + method + "\"}");
    }
}
