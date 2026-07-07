package com.testgen.runner;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runner ekranının ad-hoc istek çalıştırma API'si.
 *
 * POST /api/v1/runner/execute → verilen endpoint + request'i anında koşar,
 * status / latency / header / body döner.
 */
@Tag(name = "5. Direkt Runner", description = "Ekrandan girilen endpoint ve request'i anında çalıştırır (test üretimi gerektirmez)")
@RestController
@RequestMapping("/api/v1/runner")
@RequiredArgsConstructor
public class DirectRequestController {

    private final DirectRequestService directRequestService;

    @Operation(summary = "Endpoint'e Anında İstek Gönder",
            description = "Verilen URL, method, header ve body ile HTTP isteğini hemen çalıştırır; "
                    + "status kodu, gecikme, yanıt header'ları ve gövdesini döner.")
    @PostMapping(value = "/execute",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DirectRequestService.DirectRunResult> execute(
            @RequestBody DirectRequestService.DirectRunRequest request) {
        return ResponseEntity.ok(directRequestService.execute(request));
    }
}
