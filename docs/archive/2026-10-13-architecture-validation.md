# 三项架构改造验证记录

日期：2026-10-13。本记录汇总当日已完成的验证，不代表整理文档时重新执行。阅读当前行为请使用 [当前架构](../architecture.md)，复跑命令见 [开发与验证](../development.md)。

## 实现基线

| 顺序 | 提交 | 内容 |
| --- | --- | --- |
| 1 | `86ddb48` | 每个宿主页面独立持有分页会话与筛选 |
| 2 | `7bdfe64` | HTTP 会话抽离及全源离线状态边界 |
| 3 | `e1ad8de` | 无状态 Parser、有界元数据与统一书籍映射 |

最终验证在第 3 项提交前执行，基线为 `7bdfe64` 加第 3 项改动，随后形成 `e1ad8de`。插件版本字段仍为 `2.1.1 / 35`，不能据此认为上述改动已进入同版本发行包。

## 最终执行结果

| 检查 | 结果与范围 |
| --- | --- |
| JVM 测试 | 163 项，失败、错误、跳过均为 0；包含前两项回归 |
| Android 宿主测试 | 7 项通过；官方 API 3 宿主 `1.2.3`，Android API 37 |
| Debug / Release 构建 | 均通过 |
| Debug / Release 插件 ABI 检查 | 均通过；属于构建内置引用检查 |
| Release lint | 0 错误，不表述为 0 警告 |
| 受保护行为核对 | 第 3 项保留 HTTP、正文分页、段落、图片及章节 ID 算法 |

元数据回归覆盖容量淘汰、最近写入顺序、无效详情保护、源实例隔离、摘要回退及已登记关联目标保留。先运行的失败用例确认了无效详情覆盖关联目标的问题，修复后通过。

宿主测试覆盖统一作者标签与未知日期、目录映射、各栏目摘要记录、搜索对象原地更新，以及 ID 直达和搜索重定向。测试加载实际宿主与插件类型，输入使用固定网页内容；本轮没有把这些测试算作真实网站或 UI 验收。

构建命令：

```bash
./gradlew --offline :plugin:testDebugUnitTest :plugin:assembleDebug :plugin:assembleRelease :plugin:lintRelease --console=plain
```

Debug APK SHA-256：`ed684d355240dca9bbaf92dbc94af5f39861fb0e5c6e9c8629e79d3d98c40e97`。

## 证据与边界

原始本机目录为 `/private/tmp/linovelib-metadata-20261013/`，包含 `verification.json`、`full-validation.log`、`host-tests.log` 和修复前的 `red-invalid-targets.xml`。这些临时文件不在仓库内，不保证长期存在；关键基线与结论保存在本文。

测试使用只读模式启动的独立模拟器，结束后已关闭。正式签名覆盖升级、其他宿主版本、真实站点和完整界面流程不由上述结果覆盖。三项改造已提交到本地分支；截至本记录，未推送或发布。
