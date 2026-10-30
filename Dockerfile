FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ianus-plugin-*.jar /app/ianus-plugin.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ianus-plugin.jar"]
