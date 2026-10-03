# YunGet Web 基本测试报告

测试日期：2026-10-01
测试环境：Ubuntu 24.04（沙箱），Temurin JDK 17，jar 直接运行

## 构建

- 源码：498 个 Kotlin 类，kotlinc 2.0.21 编译通过（0 错误）
- 产物：`yunget-web.jar`（21.8 MB，fat jar，含 58 个去重后依赖）
- 前端：`app.js` 经 `node --check` 语法校验通过

## 通过的测试

| # | 测试项 | 结果 |
|---|--------|------|
| 1 | `GET /api/health` | ✅ `{"ok":true}` |
| 2 | `GET /api/platforms` | ✅ 6 个平台，登录态均为 false |
| 3 | `GET /api/settings` | ✅ 返回全部默认字段 |
| 4 | `PUT /api/settings` → `GET` | ✅ 持久化并即时生效 |
| 5 | 静态页面 `/`、`/style.css`、`/app.js` | ✅ 均为 200 |
| 6 | 未登录解析分享链接 | ✅ `{"ok":false,"message":"请先登录夸克网盘"}` |
| 7 | 空 Cookie 登录 | ✅ `{"ok":false,"message":"Cookie 不能为空"}` |
| 8 | 未知平台 | ✅ `{"ok":false,"message":"未知平台"}` |
| 9 | 未知接口 | ✅ 404 `{"ok":false,"message":"接口不存在"}` |
| 10 | 空直链提交 | ✅ `{"ok":false,"message":"下载链接不能为空"}` |
| 11 | 提交直链下载任务 | ✅ 返回 taskId |
| 12 | 任务暂停 / 恢复 / 删除 | ✅ 状态流转正常 |
| 13 | 任务列表查询 | ✅ |
| 14 | 服务重启后任务恢复 | ✅ 任务记录从 tasks.json 恢复 |
| 15 | 服务重启后设置恢复 | ✅ |
| 16 | ShareLinkParser 单元测试（6 平台 + 非法输入） | ✅ 11/11 通过 |
| 17 | 夸克扫码 token 接口可达性（curl） | ✅ 返回真实 token，`status=2000000` |
| 18 | `POST /api/qrlogin/quark` | ✅ 沙箱内网络被拦截时优雅返回失败信息，服务不崩溃 |
| 19 | `POST /api/qrlogin/baidu` | ✅ `{"ok":false,"message":"该平台暂不支持扫码登录，请用 Cookie 方式"}` |
| 20 | `GET /api/qrlogin/quark/status`（未知 session） | ✅ `{"status":"expired","message":"二维码已过期，请重新获取"}` |
| 21 | `DELETE /api/qrlogin/session` | ✅ `{"done":true}` |
| 22 | 扫码 JSON 解析逻辑（token/ticket/等待态/Set-Cookie 提取） | ✅ 用真实接口样本离线验证通过 |
| 23 | `POST /api/tasks/batch-delete`（空列表） | ✅ `{"deleted":0}` |
| 24 | `GET /api/settings` 不再含 `autoDownload`（v1.0.1 已移除自动下载） | ✅ 字段已删除，旧配置文件自动兼容忽略 |
| 25 | `PUT /api/settings` 持久化（无 `autoDownload`） | ✅ GET 一致 |
| 26 | 前端 `qrcode.js` 二维码生成（node） | ✅ 正常输出 SVG |
| 27 | 前端 `app.js` / `qrcode.js` 语法 | ✅ `node --check` 通过 |
| 28 | 静态资源 `/qrcode.js`、首页含扫码弹窗/全选（自动下载开关已移除） | ✅ 均为 200，元素存在 |
| 29 | `GET /api/settings` 含 `downloadDir`（v1.0.2 新增，显示当前生效目录） | ✅ 默认目录与自定义目录均正确返回 |
| 30 | `PUT /api/settings` 持久化 `downloadDir`，无效目录拒绝保存 | ✅ 持久化一致；不可写目录返回失败信息 |
| 31 | 批量清理两种模式（v1.0.2） | ✅ `deleteFile=false` 仅删记录文件保留；`deleteFile=true` 记录与文件均删除 |

## 已知限制（非代码问题）

1. **下载成功路径在沙箱内无法实测**：沙箱网络过滤器拦截了 Java
   进程的所有出站 HTTP（返回 "Other TCP connections is turned off"），
   curl 不受影响。表现为下载任务进入失败态，错误信息清晰，
   服务不崩溃。失败路径本身已验证可用。
2. **六个网盘的真实联网解析未实测**：无真实 Cookie/Token/分享链接。
   解析协议代码完整移植自上游 Android 版（26 个文件），逻辑一致。
3. 上游网盘接口来自逆向分析，可能随官方更新失效；百度账号有风控风险。
4. **扫码登录的完整链路（扫码→确认→Cookie 落地）需真实账号实测**：
   沙箱内 Java 出站被拦截，且无可扫码的夸克/UC 账号。
   已验证：取 token 接口真实可用、二维码可正常渲染、轮询/过期/失败
   各状态处理正确、扫码成功后走现有 `saveCookie` 校验链路。
   百度/139 暂无可验证的扫码接口，保留手动填入。

## 安全提醒

- 凭证明文存于服务器本地 JSON，仅适合单用户本地部署。
- 不可未经额外鉴权直接暴露到公网。
- AGPL-3.0：对外提供网络服务时须向用户提供完整对应源码。
