# syntax=docker/dockerfile:1

# ---- build stage: compile and package (tests are run separately: they need Docker for Testcontainers) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# ---- runtime stage: JRE + Tesseract (for OCR), non-root user ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-rus \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /build/target/spring-rag-service-*.jar app.jar
USER app
EXPOSE 8080
# OCR (scanned PDFs and images) is on in this image because Tesseract is installed. Override in .env to turn it off.
ENV OCR_ENABLED=true \
    OCR_LANGUAGES=eng+rus \
    OCR_DATA_PATH=/usr/share/tesseract-ocr/5/tessdata \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# The JRE image has no curl or wget, so probe with bash's /dev/tcp.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
