    # Faza 1: Build aplikacije
    FROM eclipse-temurin:21-jdk-jammy AS build
    WORKDIR /app
    COPY . .
    RUN ./mvnw clean package -DskipTests

    # Faza 2: Runtime slika
    FROM eclipse-temurin:21-jre-jammy
    WORKDIR /app
    COPY --from=build /app/dlsm-server/target/dlsm-server-*.jar /app/server.jar
    VOLUME ["/var/lib/dlsm"]
    EXPOSE 8080 8082
    ENTRYPOINT ["java", "-jar", "/app/server.jar"]