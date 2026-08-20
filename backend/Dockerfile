# Stage 1: build the control-plane jar with Maven + JDK 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Stage 2: minimal runtime image (non-root)
FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 cicd \
    && mkdir -p /app \
    && chown -R cicd:cicd /app
USER cicd
WORKDIR /app
COPY --from=build /build/target/cicd-control-plane-0.1.0.jar /app/control-plane.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/control-plane.jar"]
