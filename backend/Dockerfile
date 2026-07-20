FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY config ./config
COPY src ./src
RUN mvn -q -DskipTests package
RUN cp "$(find target -maxdepth 1 -name 'kubeoncall-*.jar' ! -name '*.original' -print -quit)" /workspace/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 kubeoncall
COPY --from=build /workspace/app.jar /app/kubeoncall.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/kubeoncall.jar"]
