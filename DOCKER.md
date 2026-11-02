# yunget-web Docker 部署与迁移手册

面向场景：把 yunget-web 从日常电脑（如 Mac）迁到一台常开的 Docker 宿主机（如 PVE 里专门跑 Docker 的容器/虚拟机），让下载任务 7×24 在线——电脑关机、合盖、出门都不影响，手机/电脑随时打开网页看进度、下新任务。

## 架构与设计

- **官方镜像发布在 Docker Hub，部署以 pull 为主**：镜像 = JRE + 应用本体，多架构（linux/amd64、linux/arm64），部署和升级都是一条 `docker compose pull && docker compose up -d`，不用在宿主机上构建、不用手动搬 jar。
- **镜像不含任何用户数据**：账号 / 设置 / 任务 / 历史都在运行时的数据卷里，镜像可以公开拉取（拉取免登录），升级镜像不碰数据。
- **工具住数据卷**：ffmpeg / yt-dlp / Deno / Gopeed 内核都由应用自己下载、管理在数据卷里（`/data/tools/`、`/data/gopeed/`），**不进镜像**。所以有两条互不干扰的升级线：应用升级 = pull 新镜像；工具升级 = 点应用里的安装/更新按钮。
- **全部状态只有一个目录**：宿主机部署目录下的 `data/`（容器内 `/data`）装着一切——四个 json（`settings.json` 设置、`accounts.json` 账号登录态、`tasks.json` 任务、`history.json` 历史/收藏）、续传状态、工具。备份、迁移都围绕它（见「备份」）。
- **资源占用很小**：实测应用空载内存约 95 MB；compose 里给了 `JAVA_OPTS=-Xmx512m`（堆上限）与 `mem_limit: 768m`（容器硬上限），都是上限不是占用，不需要可自行调整或删除。

目录约定（部署目录，即 `docker-compose.yml` 所在目录）：

```
部署目录/
├── docker-compose.yml   ← 部署只需要这一个文件
├── update.sh            ← 可选：自动更新脚本（见「应用自动更新（可选）」）
├── data/                ← 持久化数据（容器 /data），首次启动自动生成 / 迁移时迁入
└── downloads/           ← 下载成品（容器 /downloads），自动生成
```

## 首次部署（全新安装）

1. 在装好 Docker 的机器上建一个部署目录，把仓库里的 `docker-compose.yml` 放进去（就这一个文件）。
2. 看 compose 里的 `image:` 一行：官方镜像是 `cloudwinddream/yunget-web:latest`——**若你的 Docker Hub 用户名不是 `cloudwinddream`，改成你自己的命名空间**（镜像的发布方式见「发布者上架 Docker Hub」）；直接用官方镜像则不用改。
3. 拉取并启动：

   ```bash
   docker compose pull && docker compose up -d
   ```

   预期现象：镜像拉取完成、容器 `yunget-web` 状态 running；`docker compose logs -f` 能看到应用启动日志且无报错退出。
4. 浏览器打开 `http://<宿主机地址>:8080`，应能看到 yunget-web 界面。
5. 进「设置」页，把**下载目录改成 `/downloads`** 并保存（成品会落到宿主机的 `downloads/` 目录）。
6. 进「账号」页登录各网盘（Cookie/扫码方式与桌面使用时一致）。
7. ffmpeg / yt-dlp / Deno / Gopeed 四个工具按需在设置页一键安装：它们会下到数据卷里（Linux 版），装一次长期有效；网络不直达时先在设置里填好「工具安装代理」再装（填法见「代理在容器里怎么填」）。

## 从 Mac 迁移数据

前提：Mac 是 Apple Silicon（arm64 的 macOS 二进制），Docker 宿主/PVE 一般是 x86_64 Linux——**系统和架构都不同，所以账号/设置等文本数据可以直接搬，工具的可执行文件不能搬**，按下面清单走就不会踩坑。

1. **先退出 Mac 上正在运行的应用**（关掉跑着 jar 的终端进程），保证四个 json 都写完落盘，避免拷到写一半的状态。
2. **把 `~/.yunget-web` 的内容同步到部署目录的 `data/`**。在 Mac 终端执行（把占位符换成你的实际值；注意源路径末尾的 `/` 表示"拷内容"，最终四个 json 应直接位于 `data/` 根下，不要多套一层 `.yunget-web` 文件夹）：

   ```bash
   rsync -av ~/.yunget-web/ <用户名>@<Docker主机地址>:<部署目录>/data/
   ```

   没有 rsync 环境时，用 `scp -r ~/.yunget-web/. <用户名>@<Docker主机地址>:<部署目录>/data/` 效果相同。
3. **删掉迁入数据里的 macOS 可执行文件**（在 Docker 宿主机上、部署目录里执行）。这些是 arm64 macOS 二进制，在 x86 Linux 容器里无法运行，留着也只会干扰探测；其余内容（四个 json、续传状态、Gopeed 任务元数据）全部保留：

   ```bash
   # 三个托管工具的二进制本体（各目录里的 version.txt 是纯文本版本记录，留删皆可）
   rm -f data/tools/ffmpeg/ffmpeg data/tools/deno/deno data/tools/yt-dlp/yt-dlp
   # Gopeed 内核整目录删除（内核二进制在里面）；同目录的 storage/ 是任务元数据库，不要删
   rm -rf data/gopeed/kernel
   # 进程/日志/版本残留（可选，删了更干净）
   rm -f data/gopeed/engine.pid data/gopeed/gopeed.log data/gopeed/kernel-version.txt
   ```

   说明：`data/tools/yt-dlp/cookies.txt` 留删皆可——它只是 YouTube 登录 Cookie 的落盘副本，删了应用也会从 `accounts.json` 自动重新生成，所以 **YouTube/B 站等平台不需要重新登录**。
4. **磁力任务提醒**：迁移时正在下载中的磁力任务**不保证无缝续接**（未完成状态在 Gopeed 引擎内部），建议迁之前把磁力任务下完，或记下链接到新环境重新添加。普通网盘下载任务在 `tasks.json` + 数据目录的续传分片里，迁过去可以正常续传。
5. **启动并改三处设置**：

   ```bash
   docker compose pull && docker compose up -d
   ```

   打开 `http://<宿主机地址>:8080` 后进「设置」页：
   - **下载目录** 改为 `/downloads`（原值是 Mac 路径，容器里不存在）；
   - **手动路径**：ffmpeg / yt-dlp / Deno 三处若填过 Mac 路径（如 `~/.local/bin/yt-dlp`），全部清空——清空后应用会自动回落到数据卷里的托管版；
   - **两个代理**：「工具安装代理」与「yt-dlp 代理」若原来填的是 `127.0.0.1:端口` 形式，在容器里必然失效（原因和正确填法见下文「代理在容器里怎么填」一节），按那一节改填；原来没填或填的是局域网地址则不用动；
   - 其余设置（各网盘并发/连接数、命名模板等）已随 `settings.json` 迁入，不用动。
6. **装工具并验活**：在设置页逐个一键安装 ffmpeg / yt-dlp / Deno / Gopeed（安装代理已随设置迁入生效）；进「账号」页确认各平台仍显示登录态；随便解析一个小文件下载，确认成品出现在宿主机的 `downloads/` 里、网页里任务能正常完成。
7. **Mac 上的原数据先留作备份**，在新环境稳定跑几天、确认无误后再删；在那之前不要在 Mac 上再启动旧应用接着下，避免两边任务状态分叉。

## 升级

- **升级应用**：`docker compose pull && docker compose up -d` 就完事——拉到的新镜像生效，数据、工具、登录态都在 `data/` 卷里不受影响。
- **想全自动**：把随附的 `update.sh` 挂上 cron 定时执行（本质就是定时帮你跑上面那条 pull 命令），或用 watchtower 盯着 `:latest` 自动重建，二选一，见「应用自动更新（可选）」。
- **升级工具**（yt-dlp / ffmpeg / Deno / Gopeed）：用应用内各自的「安装/更新」按钮即可，工具住在数据卷里，与镜像、与应用版本都无关；工具升级始终走应用内按钮，与 `update.sh` 无关（让工具在应用内自动更新是另一个话题，本版不做）。

## 应用自动更新（可选）

随附的 `update.sh` 可以让宿主机定时跟版。**默认不启用**——把脚本放着不用即可；想启用时按下文挂 cron。

- **放哪、怎么跑**：`update.sh` 与 `docker-compose.yml` 放同一目录（部署目录），在**宿主机**上运行（不是进容器跑）。手动试跑一次就是 `./update.sh`，它做的就是 `docker compose pull && docker compose up -d` 并打印开始/完成时间；`set -e` 保证拉取失败时不会拿旧状态硬重启。
- **挂 cron（示例，每天凌晨 4 点）**：`crontab -e` 加一行（路径换成你的部署目录）：

  ```cron
  0 4 * * * cd /path/to/deploy && ./update.sh >> update.log 2>&1
  ```

- **或用 watchtower 替代**：watchtower 是专门盯镜像更新的容器，让它监控 `yunget-web` 容器的 `:latest` 即可自动拉取重建，与本脚本二选一、不要同时用。
- **风险提示**：自动更新意味着新版的问题也会自动扩散到你的环境。建议先手动 `pull` 跟两个版本、确认新版节奏稳定后再开 cron/watchtower。

## 备份

- **全量备份 = 打包 `data/`**：先 `docker compose stop`（保证 json 落盘干净），再把整个 `data/` 目录打包拷走即可；恢复时把 `data/` 放回部署目录、 compose 就位后 `docker compose pull && docker compose up -d`。
- `downloads/` 是下载成品，按需单独备份，不属于应用状态。

## 代理在容器里怎么填

应用里有两个代理设置——「工具安装代理」（四个工具的下载、Tracker 同步用）与「yt-dlp 代理」（YouTube 解析用），都在网页「设置」里填，形式如 `http://地址:端口`。容器环境下有一个关键区别：

> **容器里的 `127.0.0.1` 指容器自己，不是宿主机、更不是你的 Mac。** 如果你在 Mac 上填的是 `127.0.0.1:7890`（指向 Mac 本机的代理），迁到容器里会直接指向容器内部、必然连不上，必须按代理实际跑在哪改填：

1. **代理跑在 Docker 宿主上**（如宿主机上开着 Clash/mihomo）：填 `http://host.docker.internal:7890`。compose 已配好 `extra_hosts` 映射，容器里这个域名就是宿主机（Linux Docker 靠这行才有此名，Docker Desktop 原生自带）。
2. **代理跑在局域网另一台机器上**（如仍用 Mac 上的 Clash）：填 `http://<那台机器的局域网 IP>:7890`，例如 `http://192.168.1.10:7890`；同时要在那台机器的代理软件里打开"允许局域网连接"（Clash 系在设置里开 Allow LAN），否则局域网其它设备连不进来。
3. **代理本身也是个容器**：与本应用在同一 compose 网络时，直接填容器名，如 `http://clash:7890`；最省事的做法是把代理服务并入同一个 `docker-compose.yml`，天然同网、按服务名互访。

端口 `7890` 只是示例，按你代理软件实际的混合/HTTP 端口填。改完记得点设置页的保存；工具安装代理改完后，工具一键安装与 Tracker 同步立即按新代理走。

## 旁路由环境（YouTube 与下载引擎的代理）

先认清应用的流量分**三路**，代理归属各不相同，配错一路就会出现"解析成功却下不动"：

| 流量 | 谁在管 | 代理来源 |
|---|---|---|
| YouTube 解析 | yt-dlp 子进程 | 设置里的「yt-dlp 代理」 |
| 四个工具的安装/更新、Tracker 同步 | 应用内 HTTP | 设置里的「工具安装代理」 |
| **全部文件下载**（含 YouTube 媒体文件与各网盘文件） | 下载引擎 | **只认 JVM 系统代理**，上面两个设置管不到它 |

家里有旁路由（软路由透明分流）时，两种配法任选其一：

**方案 A（推荐）：让 Docker 宿主走旁路由。** 把 Docker 宿主的网关指向旁路由（或在主路由上对这台宿主单独做分流）。三路流量全部经旁路由按规则走：国内网盘直连、YouTube 与 GitHub 出境；应用里两个代理设置**留空即可**；连 `docker pull` 拉镜像也一并受益（拉取走的是宿主机网络）。这是旁路由的本职用法，一次配好全家桶。

**方案 B：不动网关，逐路填。** ① 设置里「yt-dlp 代理」与「工具安装代理」都填 `http://<旁路由 IP>:<混合端口>`（如 `http://192.168.1.2:7890`）；② 给引擎加 JVM 代理：把 `docker-compose.yml` 里 `JAVA_OPTS` 换成文件中备好的注释行（带 `-Dhttp.proxyHost` / `-Dhttps.proxyHost` 指向旁路由），改完 `docker compose up -d` 重建容器生效。注意引擎代理是全局的——所有下载都先进旁路由再由它的规则决定直连还是出境，对旁路由而言这正是正常工作方式，没有副作用。

YouTube 完整清单（两种方案通用）：Deno 记得在设置页一键安装（新版 yt-dlp 没它解析不了）；登录 Cookie 随 `accounts.json` 迁移，不用重配；解析走第一路、媒体文件走第三路，上表对照着查就不会漏。

补充：`docker pull` 拉镜像走的是 Docker 宿主的网络，不认上面任何应用层设置。走方案 B 又不想改宿主网关时，给 Docker 服务单独配代理即可（与改网关是两回事）：在宿主建 `/etc/systemd/system/docker.service.d/http-proxy.conf`，写入 `[Service]` 与 `Environment="HTTP_PROXY=http://<旁路由 IP>:<端口>"`、`Environment="HTTPS_PROXY=同上"`、`Environment="NO_PROXY=localhost,127.0.0.1"`，然后 `systemctl daemon-reload && systemctl restart docker`。实在拉不动还有兜底：从能拉到的机器上 `docker save` 导出镜像 tar，拷过来 `docker load` 导入。

## 下载目录放在其它 LXC / 服务器上

可以，但分三档，差别很大：

1. **同为 PVE 下的 guest（最优）**：不要走网络共享。把目录放在 PVE 宿主的磁盘上，分别以绑定挂载（LXC 的 mp 挂载点、Docker 的 bind volume）给 Docker 容器和目标 LXC——同一个本地文件系统，零网络开销、速度与稳定性都和本地盘一样。
2. **NFS 挂载（可用）**：在 Docker 宿主上挂 NFS，再映射进容器。注意三点：① 优先 NFSv4、千兆以上局域网；下载引擎是大量分片随机写，NFS 上会比本地慢，对方重启/断网时进行中的任务会报错重试、挂载卡死时进程可能长时间阻塞；② 容器默认以 root 写文件，导出端要注意 root 映射（root_squash）导致的权限失败，必要时放行；③ B站/YouTube 的合轨会在该目录里反复读写两个分轨文件再写成品，网络盘上这部分流量翻倍。
3. **SMB/CIFS（不推荐）**：随机写表现最差，只适合实在没得选的情况。

**红线：数据卷 `/data` 必须留在 Docker 宿主本地**，绝不能放网络盘——`tasks.json`、`accounts.json` 等小文件频繁读写且带锁，网络盘上最容易出问题。放远程的只能是下载目录（`/downloads`）。

## 常见问题

- **8080 端口被占用 / 想换端口**：改 `docker-compose.yml` 里 `ports` 的**左边**数字（如 `"18080:8080"`），右边不要动；改完 `docker compose up -d` 生效。
- **容器里工具下载失败**：多半是宿主机网络访问 GitHub 不直达——在应用「设置」里填「工具安装代理」（填法见上文「代理在容器里怎么填」），再点一键安装；该设置对四个工具的安装与 Tracker 同步统一生效。
- **文件权限**：容器默认以 root 运行，`data/`、`downloads/` 里生成的文件在宿主机上属 root。宿主机侧读写这些文件时注意用 root/管理员权限，或不要手动改动 `data/` 里的文件（改设置请走网页）。
- **时间/时区不对**：compose 已设 `TZ=Asia/Shanghai`；若日志时间仍不对，检查宿主机自身时钟，并确认改完 TZ 后重建过容器（`docker compose up -d`）。
- **网页打不开先看日志**：`docker compose logs -f` 看应用是否正常启动、镜像是否拉取成功；容器反复重启时先看日志里的报错原文，再核对 `data/` 目录权限。

## 发布者上架 Docker Hub

本节是给镜像发布者（仓库维护者）看的：如何让每次发版自动把镜像推上 Docker Hub。普通使用者跳过本节。

1. **注册**：到 <https://hub.docker.com> 注册账号（免费），用户名即镜像命名空间（如 `cloudwinddream` → 镜像 `cloudwinddream/yunget-web`）。
2. **建 Access Token**：登录后进 Account Settings → Security → New Access Token，权限选 **Read & Write**，把生成的 token 保存好（只显示一次）。
3. **给 GitHub 仓库配 secret**：仓库页 Settings → Secrets and variables → Actions → New repository secret，加两个：
   - `DOCKERHUB_USERNAME`：Docker Hub 用户名；
   - `DOCKERHUB_TOKEN`：第 2 步的 Access Token。
4. **发布即自动上架**：此后每次在 GitHub 发布 Release，Actions 里的「Docker 镜像发布」工作流会自动：从该 Release 下载 `yunget-web.jar` → 构建 linux/amd64 + linux/arm64 多架构镜像 → 以 `<版本号>` 与 `latest` 两个 tag 推送到 Docker Hub。构建结果在仓库 Actions 页查看；也可以在 Actions 里手动 Run workflow 并指定任意已发布的 tag 补推。
5. **公开镜像说明**：默认推的是公开镜像——镜像里只有 JRE 与开源的应用 jar，**不含任何账号与用户数据**（那些都在使用者自己的数据卷里），任何人拉取都免登录。若想改私有：在 Docker Hub 的仓库设置里把可见性设为 Private，使用者宿主机先 `docker login` 再 pull 即可，其余流程不变。

## 自构建（可选）

不想用 Docker Hub 上的镜像、想自己构建时：从 GitHub Release 下载 `yunget-web.jar` 放到仓库 `Dockerfile` 同目录，`docker build -t yunget-web:latest .` 构建本地镜像；compose 里把 `image:` 行换成 `build: .` 并去掉（或改名）原 image 行，其余不变，`docker compose up -d --build` 启动。数据卷、工具、迁移与备份流程与 pull 模式完全一致。

备选：不想注册 Docker Hub，也可改用 GHCR（ghcr.io，GitHub 自带、免新建账号、用仓库自带 GITHUB_TOKEN 即可推送）；本套件按 Docker Hub 编排，切换只需改 workflow 的 login 方式与 tags 的命名空间。
