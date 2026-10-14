# Wanda AI Assistant - container image for cloud hosting (Render, Railway, etc.)
# Author: Saksham Johri

# Stage 1: build the runnable jar with Maven (tests run in CI, so they are skipped here)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q clean package -DskipTests

# Stage 2: run it on a small Java runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/wanda-ai.jar app.jar
ENV PORT=8080
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
