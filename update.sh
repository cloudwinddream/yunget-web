#!/usr/bin/env bash
# =============================================================================
# yunget-web 自动更新（Docker Hub pull 模式）
#
# 在 **Docker 宿主机** 上运行（不是容器内），与 docker-compose.yml 同目录：
# 拉取最新镜像并重建容器 —— 等价于手动执行 docker compose pull && up -d。
# 数据与工具都在 data/ 卷里，更新不影响。
#
# 挂 cron 示例（每天凌晨 4 点，路径换成你的部署目录）：
#   0 4 * * * cd /path/to/deploy && ./update.sh >> update.log 2>&1
#
# 想全自动可用 watchtower 替代本脚本：让它盯着 yunget-web 容器的 :latest
# 镜像自动拉取重建即可，二选一、不要同时用。
# 风险提示：自动跟版意味着新版的问题也会自动生效，介意的话请手动 pull。
# =============================================================================
set -euo pipefail

# ---- 可改变量区 -------------------------------------------------------------
# 部署目录（默认 = 本脚本所在目录，一般不用改）
DEPLOY_DIR="${DEPLOY_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
# compose 文件位置（与脚本不在同目录时改这里）
COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.yml}"
# -----------------------------------------------------------------------------

echo "[update] $(date '+%F %T') 开始：拉取最新镜像…"
docker compose -f "$COMPOSE_FILE" pull
docker compose -f "$COMPOSE_FILE" up -d
echo "[update] 完成时间：$(date '+%F %T')"
