# Imagem do backend para o Railway (0,5 vCPU / 1 GB). Build em duas etapas:
#   1. Maven compila o jar (dependências numa camada própria, reaproveitada enquanto o pom.xml não muda).
#   2. JRE 21 enxuto; o jar é "extraído" (lib/ + jar fino) e uma rodada de treino gera o arquivo CDS de
#      classes compartilhadas, que corta o tempo de subida e a memória de metaspace num servidor tão pequeno.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/backend-*.jar extract --destination extracted --application-filename app.jar

FROM eclipse-temurin:21-jre
# Sem LANG UTF-8 a JVM quebra em nomes de arquivo acentuados; TZ define o "hoje" dos lançamentos e recorrências.
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=America/Sao_Paulo
# Memória: heap em 60% de 1 GB, o resto fica para metaspace, pilhas e buffers de imagem (ImageIO decodifica a
# foto inteira na memória). SerialGC e só o compilador C1 (TieredStopAtLevel=1): num 0,5 vCPU o JIT C2 disputa
# CPU com o próprio app; com uma pessoa usando, o pico de throughput do C2 não faz falta. Pode ser ajustado
# pela variável JAVA_TOOL_OPTIONS no Railway sem rebuild.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
RUN useradd --system --uid 1001 --no-create-home app
WORKDIR /app
# --chown aqui, e não um chown -R depois: um chown posterior duplicaria toda a camada da aplicação na imagem.
COPY --from=build --chown=app:app /build/extracted/ ./
# Treino do CDS: sobe o contexto sem banco (profile cds) e sai, gravando as classes carregadas em app.jsa.
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -Dspring.profiles.active=cds -jar app.jar \
 && chown app:app app.jsa
USER app
# Railway injeta PORT; o Spring lê ${PORT:8080}.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]
