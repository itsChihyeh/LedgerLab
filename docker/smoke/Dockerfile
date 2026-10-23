FROM eclipse-temurin:25-jre
WORKDIR /app
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
