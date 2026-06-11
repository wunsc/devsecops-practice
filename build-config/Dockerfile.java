FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build
ARG SERVICE_NAME=order-service
USER root
WORKDIR /build
COPY pom.xml .
COPY src/ src/
RUN mvn package -DskipTests -B -Dmaven.repo.local=/build/.m2

FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
ARG SERVICE_NAME=order-service
ARG APP_PORT=8080

USER root
WORKDIR /app
COPY --from=build /build/target/${SERVICE_NAME}.jar /app/app.jar

RUN curl -fsSL -o /app/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.4.0/opentelemetry-javaagent.jar && \
    chmod 644 /app/opentelemetry-javaagent.jar && \
    chown 185:0 /app/opentelemetry-javaagent.jar /app/app.jar

ENV SERVER_PORT=${APP_PORT} \
    JAVA_OPTS_APPEND="-javaagent:/app/opentelemetry-javaagent.jar"

EXPOSE ${APP_PORT}
USER 185

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS_APPEND} -jar /app/app.jar --server.port=${SERVER_PORT}"]
