# Stage 1: Build
FROM maven:3.9.5-eclipse-temurin-17 AS build
WORKDIR /app

# Copiar archivos de configuracion de Maven
COPY pom.xml .
# Descargar dependencias (esto se cachea si pom.xml no cambia)
RUN mvn dependency:go-offline -B

# Copiar el codigo fuente
COPY src ./src

# Compilar la aplicacion
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copiar el jar desde el stage de build
COPY --from=build /app/target/*.jar app.jar

# Exponer puerto
EXPOSE 8080

# Ejecutar la aplicacion
ENTRYPOINT ["java", "-jar", "app.jar"]