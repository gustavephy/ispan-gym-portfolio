# Build stage using GraalVM/OpenJDK 25 from GHCR
FROM ghcr.io/graalvm/jdk-community:25 AS builder
WORKDIR /app

# Copy maven wrapper & pom
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Copy source code and build jar
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Run stage using GraalVM/OpenJDK 25
FROM ghcr.io/graalvm/jdk-community:25
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
