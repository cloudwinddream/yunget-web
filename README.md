# YunGet 网页版

网盘分享解析 + 高速下载的 Web 服务。由 Android 开源项目
[YunGet](https://github.com/jiayuxuan123/YunGet) 移植：**网盘协议解析层代码直接复用**，
下载引擎为纯 JVM 的 [TurboDL](https://github.com/jiayuxuan123/TurboDL)，
UI 从 Jetpack Compose 重写为网页。

支持：夸克网盘 / UC 网盘 / 迅雷网盘 / 百度网盘 / 123 云盘 / 139 云盘（和彩云）。

## 功能

- 分享链接解析（自动识别 6 种网盘，支持提取码）
- 分享文件目录浏览、批量勾选下载
- 多线程分片下载、断点续传、下载失败自动重试
- 下载任务管理：暂停 / 继续 / 删除 / 进度 / 速度
- 下载完成后经浏览器取回文件
- 普通 http/https 直链下载
- 下载设置：每任务连接数、并发任务数、全局限速、重试次数

## 快速开始

### 方式一：直接运行（推荐）

只需要安装 Java 17+：

```bash
java -jar yunget-web.jar
```

浏览器打开 http://localhost:8080 即可。

- 数据目录：`~/.yunget-web`（下载文件、账号凭证、任务记录）
- 端口：默认 `8080`，可用环境变量 `PORT` 修改
- 数据目录可用环境变量 `YUNGET_DATA_DIR` 修改

### 方式二：Docker Compose

```bash
docker compose up -d --build
```

浏览器打开 http://localhost:8080。下载文件保存在 `./data/downloads`。

### 方式三：从源码构建

需要 JDK 17、curl、python3：

```bash
./build.sh
java -jar yunget-web.jar
```

构建脚本会自动下载 Kotlin 编译器，按 Maven 规则解析并下载依赖，
用 kotlinc 直接编译后打包为单文件 `yunget-web.jar`。

## 账号登录

| 网盘 | 登录方式 |
|------|----------|
| 夸克 / UC / 百度 / 139 | 粘贴 Cookie（替代 App 内 WebView 登录） |
| 迅雷 | 账号密码（新设备首次登录需短信验证，按页面提示操作） |
| 123 云盘 | 账号密码 |

**Cookie 获取方法**（以夸克为例）：
1. 电脑浏览器打开 `pan.quark.cn` 并登录
2. 按 `F12` 打开开发者工具 → `网络（Network）` 标签
3. 刷新页面，任选一个请求，复制请求头中的 `Cookie` 整串
4. 粘贴到网页「账号」页对应网盘的输入框，点「保存并验证」

凭证只保存在服务器本地的 `accounts.json`，不会上传到任何第三方。

## API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/platforms | 平台列表及登录状态 |
| POST | /api/accounts/{platform} | 登录（Cookie / 账号密码） |
| POST | /api/accounts/xunlei/sms | 迅雷发送短信验证码 |
| POST | /api/accounts/xunlei/sms-login | 迅雷短信验证登录 |
| DELETE | /api/accounts/{platform} | 退出登录 |
| POST | /api/parse | 解析分享链接 `{link, pwd}` |
| GET | /api/sessions/{id}/files?dirFid= | 分享文件列表 |
| POST | /api/downloads | 批量取直链并下载 `{sessionId, files}` |
| POST | /api/downloads/direct | 普通直链下载 |
| GET | /api/tasks | 下载任务列表 |
| POST | /api/tasks/{id}/pause | 暂停 |
| POST | /api/tasks/{id}/resume | 继续 |
| DELETE | /api/tasks/{id}?deleteFile= | 删除任务 |
| GET | /api/tasks/{id}/file | 取回已完成的文件 |
| GET/PUT | /api/settings | 下载设置 |

## 项目结构

```
yunget-web/
├── backend/                    # Ktor 后端 + 网页前端
│   └── src/main/
│       ├── kotlin/com/yunget/
│       │   ├── app/data/       # 从 YunGet 移植：网盘协议解析（network + repository）
│       │   └── web/            # 新增：Main / routes / service / model
│       └── resources/static/   # 网页前端（index.html / app.js / style.css）
├── turbodl-core/               # TurboDL 下载引擎（MIT）
├── turbo-plugin-runtime/
├── turbo-plugin-bootstrap/
├── turbo-plugin-hls/
├── Dockerfile / docker-compose.yml
└── LICENSE / NOTICE
```

## 注意事项

1. **网盘接口来自逆向分析**，可能随官方改版而失效；百度网盘还存在账号风控风险，请使用小号。
2. **服务器代下载会产生带宽和存储成本**，建议部署在自己的机器或 VPS 上。
3. 本服务默认无鉴权，**不要直接暴露到公网**；如需公网访问，请自行加反向代理 + 密码保护。

## 开源许可

本项目基于 [YunGet](https://github.com/jiayuxuan123/YunGet) 二次开发，
遵循 **GNU Affero General Public License v3.0（AGPL-3.0）**，
详见 `LICENSE`。上游归属见 `NOTICE`。

根据 AGPL-3.0，如果你把本项目作为网络服务提供给他人使用，
必须向使用者提供本项目的完整对应源码。

内含的 TurboDL 下载引擎为 **MIT** 许可（见 `LICENSE.TurboDL`），
版权归 jiayuxuan123 及 TurboDL 贡献者所有。
