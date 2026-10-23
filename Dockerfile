FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x gradlew && ./gradlew :app:installDist --no-daemon

ENV PORT=8081

EXPOSE 8081

CMD ["./app/build/install/app/bin/app"]
