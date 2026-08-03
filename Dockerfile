# ─────────────────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────────────────
FROM maven:3-amazoncorretto-17-alpine AS builder

WORKDIR /build

# Bağımlılık cache katmanı — pom değişmezse bu katman tekrar indirilmez
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Kaynak kodu kopyala ve build et
COPY src ./src
RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────────────────
FROM maven:3-amazoncorretto-17-alpine

WORKDIR /app

# Chromium (headless Selenium için) + wget healthcheck için
RUN apk update && apk add --no-cache \
    chromium \
    chromium-chromedriver \
    udev \
    ttf-freefont \
    wget \
    ca-certificates \
    && rm -rf /var/cache/apk/*

ENV CHROME_BIN=/usr/bin/chromium-browser
ENV CHROME_DRIVER=/usr/bin/chromedriver
ENV MAVEN_CONFIG=/home/testgen/.m2
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Non-root kullanıcı — güvenlik best practice
RUN addgroup -S testgen && adduser -S -h /home/testgen -G testgen testgen

# Çalışma dizinleri
RUN mkdir -p /tmp/generated-tests/karate \
             /tmp/generated-tests/selenium \
             /tmp/generated-tests/allure-results \
             /tmp/generated-tests/allure-report \
             /home/testgen/.m2 \
             /app/target \
    && chown -R testgen:testgen /tmp/generated-tests /home/testgen /app

USER testgen

COPY --from=builder --chown=testgen:testgen /build/target/ai-test-generator-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
