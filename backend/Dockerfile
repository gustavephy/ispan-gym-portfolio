# Build stage
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Copy maven wrapper & pom
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Download dependencies for layer caching
RUN ./mvnw dependency:go-offline -B || true

# Copy source code and build jar
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
