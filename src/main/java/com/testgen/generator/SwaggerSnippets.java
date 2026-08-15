package com.testgen.generator;

import io.swagger.v3.oas.models.Operation;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt'a giden OpenAPI özet parçalarını üretir.
 *
 * NEDEN VAR: Ölçülen bir koşumda üretilen testlerin çoğu, spec'in hiç bildirmediği
 * status kodlarını beklediği için düşüyordu (auth'suz bir uç için 401, sağlıklı bir
 * uç için 400/500). Sebep prompt'taki senaryo şablonu değil, GİRDİYDİ: operasyon
 * özeti prompt'a yazılırken <b>responses bölümü tamamen atlanıyordu</b>. Model hangi
 * kodların gerçek olduğunu göremeyince şablondaki numaraları uyduruyordu.
 *
 * Bildirilen kodlar prompt'a yazıldığında "yalnızca bildirileni doğrula" kuralının
 * bağlanacağı somut bir dayanak oluşur.
 */
final class SwaggerSnippets {

    private SwaggerSnippets() {}

    /**
     * Operasyonun bildirdiği yanıt kodlarını YAML benzeri satırlar olarak döner.
     * Hiç yanıt bildirilmemişse, modelin boşluğu doldurmaması için bunu açıkça yazar.
     */
    static String declaredResponses(Operation op) {
        List<String> codes = new ArrayList<>();
        if (op != null && op.getResponses() != null) {
            op.getResponses().forEach((code, response) -> {
                String description = response == null || response.getDescription() == null
                        ? "" : " # " + response.getDescription();
                codes.add("        \"" + code + "\":" + description);
            });
        }

        if (codes.isEmpty()) {
            return """
                          responses: (spec hicbir status bildirmiyor)
                    # DIKKAT: Bildirilen status yok. Yalnizca basarili yanit senaryosu yaz;
                    # 400/401/404/500 gibi kodlari UYDURMA.
                    """;
        }

        return "      responses:\n" + String.join("\n", codes) + "\n"
                + "# DIKKAT: Yukaridakiler bu ucun BILDIRDIGI TEK status kodlaridir.\n"
                + "# Listede olmayan bir status'u bekleyen senaryo YAZMA.\n";
    }
}
