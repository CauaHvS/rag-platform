# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Baixa dependências primeiro (cache layer separado do código)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Compila e empacota sem rodar testes (ITs requerem infra externa)
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuário não-root para segurança (never run as root in production)
RUN addgroup -S ragplatform && adduser -S ragplatform -G ragplatform

COPY --from=build /app/target/*.jar app.jar

USER ragplatform
EXPOSE 8080

# Flags JVM: container-aware memory limits + virtual threads já habilitados no yml
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
