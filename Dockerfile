# Stage 1: build every module with the Maven wrapper
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
# A Windows checkout may have CRLF line endings and no exec bit.
RUN sed -i 's/\r$//' mvnw .mvn/wrapper/maven-wrapper.properties \
    && chmod +x mvnw \
    && ./mvnw -q -B package -DskipTests

# Stage 2: the database service (compose service `standalone`)
FROM eclipse-temurin:21-jre-jammy AS standalone
WORKDIR /app
COPY --from=build /app/dlsm-server/target/dlsm-server-*.jar /app/server.jar
VOLUME ["/var/lib/dlsm"]
EXPOSE 8080 8082
ENTRYPOINT ["java", "-jar", "/app/server.jar"]

# Stage 3: the student test runner (PI §25.2). It starts the same production
# server.jar as a child process over an isolated directory and ports.
FROM eclipse-temurin:21-jre-jammy AS student-tests
WORKDIR /app
COPY --from=build /app/dlsm-server/target/dlsm-server-*.jar /app/server.jar
COPY --from=build /app/dlsm-tests/target/dlsm-tests-*.jar /app/tests.jar
ENV DLSM_SERVER_JAR=/app/server.jar
ENTRYPOINT ["java", "-jar", "/app/tests.jar"]
