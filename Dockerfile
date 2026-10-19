FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/alarm-service-1.0.0.jar app.jar
RUN mkdir /archives && chown 10001:10001 /archives
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
