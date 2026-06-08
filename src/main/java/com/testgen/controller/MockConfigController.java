package com.testgen.controller;

import com.testgen.model.MockResponse;
import com.testgen.repository.MockResponseRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "3. Mock API Konfigürasyonu", description = "Karate testleri için simüle edilmiş HTTP yanıtlarını yöneten API'ler")
@RestController
@RequestMapping("/api/v1/mock-configs")
@RequiredArgsConstructor
public class MockConfigController {

    private final MockResponseRepository mockResponseRepository;

    @Operation(summary = "Yeni Mock API Yanıtı Kaydet / Güncelle", description = "Karate testlerinin kullanması için veritabanına simüle edilmiş HTTP response (path, method, status, body) verisi yazar.")
    @PostMapping
    public ResponseEntity<MockResponse> registerMock(@RequestBody MockResponse mockResponse) {
        if (!mockResponse.getPath().startsWith("/")) {
            mockResponse.setPath("/" + mockResponse.getPath());
        }
        mockResponse.setMethod(mockResponse.getMethod().toUpperCase());

        var existing = mockResponseRepository.findByPathAndMethod(mockResponse.getPath(), mockResponse.getMethod());
        if (existing.isPresent()) {
            var mock = existing.get();
            mock.setStatusCode(mockResponse.getStatusCode());
            mock.setResponseBody(mockResponse.getResponseBody());
            return ResponseEntity.ok(mockResponseRepository.save(mock));
        }

        return ResponseEntity.ok(mockResponseRepository.save(mockResponse));
    }

    @Operation(summary = "Kayıtlı Tüm Mock API Yanıtlarını Listele", description = "Veritabanındaki simüle edilmiş tüm mock API yanıt tanımlarını getirir.")
    @GetMapping
    public ResponseEntity<List<MockResponse>> listMocks() {
        return ResponseEntity.ok(mockResponseRepository.findAll());
    }

    @Operation(summary = "Belirli Bir Mock API Kaydını Sil", description = "Seçilen ID'ye ait mock API yanıt kaydını veritabanından siler.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMock(@PathVariable String id) {
        mockResponseRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Tüm Mock API Kayıtlarını Temizle", description = "Veritabanındaki simüle edilmiş tüm mock API yanıtlarını tamamen siler.")
    @DeleteMapping
    public ResponseEntity<Void> clearMocks() {
        mockResponseRepository.deleteAll();
        return ResponseEntity.ok().build();
    }
}
