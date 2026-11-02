# =============================================================================
# yunget-web 官方镜像：JRE + 应用本体（发布到 Docker Hub，部署以 pull 为主）
#
# - 构建上下文里必须有 yunget-web.jar：CI（.github/workflows/docker-publish.yml）
#   会从对应的 GitHub Release 资产下载；本地自构建时把 Release 的 jar 放到
#   本文件同目录即可。
# - 镜像里**只有 JRE 与应用 jar**，不含任何用户数据：账号 / 设置 / 任务 /
#   历史都在运行时的数据卷 /data 里；四个托管工具（ffmpeg / yt-dlp / Deno /
#   Gopeed 内核）由应用下载到 /data/tools、/data/gopeed，同样不进镜像。
#   因此镜像可以公开拉取，升级镜像不会碰用户数据。
# - 基础镜像 eclipse-temurin:17-jre 为多架构镜像（amd64 / arm64）。
# =============================================================================
FROM eclipse-temurin:17-jre

WORKDIR /app

# 应用本体（构建时由 CI 或自构建者放进上下文的 Release jar）
COPY yunget-web.jar /app/yunget-web.jar

# 数据目录与端口（与 docker-compose.yml 的 environment 保持一致）
ENV YUNGET_DATA_DIR=/data
ENV PORT=8080
# 中文文件名需要 UTF-8 locale
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

EXPOSE 8080

# sh -c 形式启动，以便展开 $JAVA_OPTS（如 -Xmx512m，由 compose 注入）；
# exec 让 java 成为容器主进程，docker stop 的信号能直达应用优雅退出。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/yunget-web.jar"]
