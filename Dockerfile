# Backend DOZZER para Render (plan gratis: 512 MB de memoria)

# 1) Compilar con Maven
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/*.jar app.jar

# 2) Imagen final, solo con Java para ejecutar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar

# Memoria ajustada a 512 MB: deja espacio fuera del heap para Java y Postgres SSL
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom"

# Render asigna el puerto en la variable PORT
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
