package com.testgen.runner;

import com.testgen.model.TestFramework;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class GeneratedJavaTestProjectService {

    @Value("${test-generator.output.selenium-path}")
    private String seleniumOutputPath;

    @Value("${test-generator.output.appium-path}")
    private String appiumOutputPath;

    @Value("${selenium.version:4.18.1}")
    private String seleniumVersion;

    @Value("${appium.version:9.1.0}")
    private String appiumVersion;

    public Path ensureProject(TestFramework framework) {
        Path projectDir = projectDir(framework);
        try {
            Files.createDirectories(sourceDir(projectDir));
            Files.writeString(projectDir.resolve("pom.xml"), pomFor(framework), StandardCharsets.UTF_8);
            return projectDir;
        } catch (IOException e) {
            throw new IllegalStateException("Generated test Maven projesi hazirlanamadi: " + projectDir, e);
        }
    }

    public Path writeTestSource(TestFramework framework, String fileName, String content) {
        Path projectDir = ensureProject(framework);
        Path sourceFile = sourceDir(projectDir).resolve(fileName);
        try {
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, content, StandardCharsets.UTF_8);
            return sourceFile;
        } catch (IOException e) {
            throw new IllegalStateException("Generated Java test dosyasi yazilamadi: " + sourceFile, e);
        }
    }

    public Path projectDir(TestFramework framework) {
        return switch (framework) {
            case SELENIUM -> Path.of(seleniumOutputPath);
            case APPIUM -> Path.of(appiumOutputPath);
            default -> throw new IllegalArgumentException("Java test projesi desteklenmiyor: " + framework);
        };
    }

    private Path sourceDir(Path projectDir) {
        return projectDir.resolve("src/test/java");
    }

    private String pomFor(TestFramework framework) {
        String extraDependency = framework == TestFramework.APPIUM
                ? """
                        <dependency>
                            <groupId>io.appium</groupId>
                            <artifactId>java-client</artifactId>
                            <version>%s</version>
                            <scope>test</scope>
                        </dependency>
                  """.formatted(appiumVersion)
                : "";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.testgen.generated</groupId>
                    <artifactId>%s-generated-tests</artifactId>
                    <version>1.0.0</version>

                    <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <junit.version>5.10.2</junit.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>${junit.version}</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.seleniumhq.selenium</groupId>
                            <artifactId>selenium-java</artifactId>
                            <version>%s</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>io.github.bonigarcia</groupId>
                            <artifactId>webdrivermanager</artifactId>
                            <version>5.8.0</version>
                            <scope>test</scope>
                        </dependency>
                %s
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                                <configuration>
                                    <useModulePath>false</useModulePath>
                                    <failIfNoSpecifiedTests>false</failIfNoSpecifiedTests>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(framework.name().toLowerCase(), seleniumVersion, extraDependency);
    }
}
