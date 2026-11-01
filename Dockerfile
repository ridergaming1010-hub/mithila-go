FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod +x mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/mith-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/data
VOLUME ["/app/data"]
ENV PORT=8081
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]