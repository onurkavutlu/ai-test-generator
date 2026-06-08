package com.testgen.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger / OpenAPI Grouping and Ordering Configuration
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi allApis() {
        return GroupedOpenApi.builder()
                .group("1-all-apis")
                .displayName("1. Tüm API'ler")
                .pathsToMatch("/api/v1/**")
                .build();
    }

    @Bean
    public GroupedOpenApi demoGroup() {
        return GroupedOpenApi.builder()
                .group("2-demo-akisi")
                .displayName("2. Sunum ve Demo Akışı (Adım Adım)")
                .pathsToMatch(
                        "/api/v1/tests/generate",
                        "/api/v1/tests/{requestId}",
                        "/api/v1/tests/{requestId}/cases",
                        "/api/v1/tests/{requestId}/run-all",
                        "/api/v1/scheduler/{requestId}/trigger-now"
                )
                .addOpenApiCustomizer(demoOpenApiCustomizer())
                .build();
    }

    @Bean
    public GroupedOpenApi schedulerGroup() {
        return GroupedOpenApi.builder()
                .group("3-scheduler")
                .displayName("3. Scheduler & Self-Healing")
                .pathsToMatch("/api/v1/scheduler/**")
                .build();
    }

    @Bean
    public GroupedOpenApi devGroup() {
        return GroupedOpenApi.builder()
                .group("4-dev-tools")
                .displayName("4. Geliştirici Araçları & Seed")
                .pathsToMatch("/api/v1/dev/**", "/api/v1/mock-configs/**")
                .build();
    }

    private OpenApiCustomizer demoOpenApiCustomizer() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths == null) return;

            // Define the strict order we want
            List<String> order = List.of(
                    "/api/v1/tests/generate",
                    "/api/v1/tests/{requestId}",
                    "/api/v1/tests/{requestId}/cases",
                    "/api/v1/tests/{requestId}/run-all",
                    "/api/v1/scheduler/{requestId}/trigger-now"
            );

            Paths orderedPaths = new Paths();
            // First add the ones in the ordered list if they exist
            for (String pathKey : order) {
                if (paths.containsKey(pathKey)) {
                    var pathItem = paths.get(pathKey);
                    // Refinement: For cases path, we only want GET (index/list), not manual POST creation
                    if ("/api/v1/tests/{requestId}/cases".equals(pathKey)) {
                        pathItem.setPost(null);
                    }
                    // Override tags to be exactly: "Sunum ve Demo Akışı (Adım Adım)"
                    pathItem.readOperations().forEach(operation -> {
                        operation.setTags(List.of("Sunum ve Demo Akışı (Adım Adım)"));
                    });
                    orderedPaths.put(pathKey, pathItem);
                }
            }



            openApi.setPaths(orderedPaths);
        };
    }
}
