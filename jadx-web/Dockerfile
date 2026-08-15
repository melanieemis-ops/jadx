FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon dist
RUN mkdir -p /tmp/webclasses && javac -d /tmp/webclasses jadx-web/JadxWebServer.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/jadx /app/jadx
COPY --from=build /tmp/webclasses /app/webserver
COPY jadx-web/web /app/web
ENV JADX_BIN=/app/jadx/bin/jadx
ENV STATIC_ROOT=/app/web
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "/app/webserver", "JadxWebServer"]
