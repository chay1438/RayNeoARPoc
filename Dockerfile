# Build Stage
FROM gradle:8.7-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
# Build the server distribution (this creates an executable zip/tar)
RUN ./gradlew :server:installDist --no-daemon

# Run Stage
FROM openjdk:17-jdk-slim
EXPOSE 8080
# Copy the built distribution from the build stage
COPY --from=build /home/gradle/src/server/build/install/server /app
WORKDIR /app

# Run the Ktor server
CMD ["./bin/server"]
