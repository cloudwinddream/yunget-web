# ---- 构建阶段（kotlinc 直接编译，已验证） ----
FROM eclipse-temurin:17-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
      curl unzip python3 ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . .
ENV TOOLS_DIR=/opt/tools
RUN chmod +x build.sh && ./build.sh

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/yunget-web.jar ./yunget-web.jar
ENV YUNGET_DATA_DIR=/data
ENV PORT=8080
VOLUME /data
EXPOSE 8080
CMD ["java", "-Xmx1g", "-jar", "yunget-web.jar"]
