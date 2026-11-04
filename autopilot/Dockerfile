# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package && cp target/ads-autopilot-*.jar /app.jar

# Run: the same image serves the MCP server and approval page, or runs the watchdog once
# (SPRING_PROFILES_ACTIVE=watchdog). No credentials are baked in: configuration comes from the environment and
# Google credentials from Workload Identity.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home autopilot
COPY --from=build /app.jar /app/app.jar
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
