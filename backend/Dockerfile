# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /workspace
COPY pom.xml ./
COPY config ./config
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -DskipTests package
RUN cp "$(find target -maxdepth 1 -name 'kubeoncall-*.jar' ! -name '*.original' -print -quit)" /workspace/app.jar

FROM eclipse-temurin:17-jre@sha256:92999aea37688157a53a40bfcb187c30f317422e028045fd5fc5c548fde9e626
WORKDIR /app
RUN useradd --system --uid 10001 kubeoncall
COPY --from=build /workspace/app.jar /app/kubeoncall.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/kubeoncall.jar"]
