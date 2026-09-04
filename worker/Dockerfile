# Stage 1: build the worker jar with Maven + JDK 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Stage 2: minimal runtime image (non-root)
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 cicd \
    && mkdir -p /app /data/workspaces \
    && chown -R cicd:cicd /app /data
USER cicd
WORKDIR /app
COPY --from=build /build/target/cicd-worker-0.1.0.jar /app/worker.jar
EXPOSE 8080
ENV WORKSPACE_ROOT=/data/workspaces
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/worker.jar"]
