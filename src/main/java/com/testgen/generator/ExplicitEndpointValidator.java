package com.testgen.generator;

import com.testgen.config.BadRequestException;
import com.testgen.model.TestGenerationRequest;

import java.net.URI;

/** GraphQL/SOAP gibi payload içinde hedef taşımayan protokoller için ortak doğrulama. */
final class ExplicitEndpointValidator {

    private ExplicitEndpointValidator() {
    }

    static String requireHttpUrl(TestGenerationRequest request, String inputType) {
        String endpoint = request == null ? null : request.getApplicationUrl();
        if (endpoint == null || endpoint.isBlank()) {
            throw new BadRequestException(inputType
                    + " üretimi için applicationUrl içinde gerçek endpoint zorunludur — endpoint uydurulmaz.");
        }

        try {
            URI uri = URI.create(endpoint.strip());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("absolute HTTP(S) URL required");
            }
            return uri.toString();
        } catch (IllegalArgumentException error) {
            throw new BadRequestException(inputType
                    + " applicationUrl geçerli mutlak HTTP(S) URL olmalıdır: " + endpoint);
        }
    }
}
