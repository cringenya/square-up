FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system squareup && useradd --system --gid squareup squareup
WORKDIR /app
COPY --from=build /src/build/install/square-up-server/ /app/
USER squareup
EXPOSE 8080
ENTRYPOINT ["/app/bin/square-up-server"]
