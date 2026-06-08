package com.testgen.runner;

import com.intuit.karate.junit5.Karate;

/**
 * Bu sınıf CI/CD pipeline'ından veya manuel olarak regression testlerini tetiklemek için kullanılır.
 * "Test" veya "Tests" son ekiyle bitmediği için varsayılan "mvn test" aşamasında çalıştırılmaz.
 * Sadece -Dtest=com.testgen.runner.RegressionRunner parametresi geçildiğinde çalışır.
 */
public class RegressionRunner {

    @Karate.Test
    public Karate runRegression() {
        return Karate.run("classpath:regression/regression.feature");
    }
}
