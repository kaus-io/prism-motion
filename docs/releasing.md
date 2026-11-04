# CI 发布

## 产物与版本

软件版本唯一来源是 [gradle.properties](<../gradle.properties>) 的 `version`。发布标签为 `v<version>`，标签、CLI 横幅与 Maven 版本必须一致；预发布使用 `-alphaNN` / `-betaNN` / `-rcNN`，不复用已经对外发布的版本。

当前工作流专用于 SDK 与 Prism 的首次同版本发布。后续版本必须改为消费已经公开的冻结 SDK 包，不能照搬首发流程重传同一 SDK 版本；发布器也不会假定 beta、rc 或正式版已被这次验证覆盖。

首发包含：

- Linux x86_64 原生命令行：打包、探测、校验，内嵌 ASTC 工具。
- Windows x86_64 原生命令行：探测、校验与内嵌工具；PNG/H.264 打包目前未接入。
- Maven Central 的 `com.kaus-io` 库：Android、JVM，以及已声明的 Linux/Windows Kotlin/Native 目标。macOS 不在首发承诺内。
- CLI 的许可证、第三方声明、公开验签密钥与 SHA-256 校验清单。CLI 以原生文件直接下载，不要求解压分发包。

客户端使用 `mavenCentral()`，并显式指定所需版本。例如：

```kotlin
val prismMotionVersion: String by project

dependencies {
    implementation("com.kaus-io:prism-motion-compose:$prismMotionVersion")
    implementation("com.kaus-io:prism-motion-engine-kap:$prismMotionVersion")
    implementation("com.kaus-io:prism-motion-engine-vap:$prismMotionVersion")
}
```

内部 JVM 工具项目发布为 `prism-motion-native-loader`，不使用容易与 KMP 目标重名的 `utils-jvm`。JVM ASTC/H.264 软件解码仍需要已说明的本机原生库；Maven 依赖不等同于分发任意桌面平台的编解码动态库。

## 发布凭据

[工作流](<../.github/workflows/release.yml>) 使用下列组织或仓库 Actions Secrets；组织 Secrets 必须允许本仓库访问：

| Secret | 用途 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal 发布令牌用户名 |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal 发布令牌密码 |
| `GPG_KEY_CONTENTS` | 导出的 ASCII-armored 私钥；也支持其 base64 文本 |
| `SIGNING_KEY_ID` | 签名密钥 ID / 指纹，构建时规范为 Gradle 支持的短 ID |
| `SIGNING_PASSWORD` | 私钥口令 |

账号须已验证 `com.kaus-io` 命名空间，签名公钥须能被 Central 验证。不要在聊天、仓库、命令行参数或日志中粘贴私钥和令牌。`NPM_TOKEN` 不参与该流程。

[签名准备器](<../scripts/prepare-release-signing.py>) 只在 Runner 的受保护环境文件中传递内存签名参数。暂存后还会在一次性密钥环中逐项[实际验签](<../scripts/verify-release-signatures.py>)，只导出公开密钥。Gradle 配置缓存关闭，不把签名输入保存进构建缓存。

## 可复现的依赖与门禁

KAP / VAP 的 Region 接口源码冻结在 [SDK 快照](<../thirdparty/prism-sdk/PROVENANCE.json>)。只复制与发布依赖闭包相关的模块，保留 MIT 与 Zstandard BSD 声明，不包含 Hanna 其它代码、未推送历史、构建产物或机器配置。

1. [快照校验器](<../thirdparty/prism-sdk/verify_snapshot.py>) 检查来源清单、生产源码摘要与法律文件的唯一打包路径。
2. Gradle 9.7.1 独立构建 SDK 快照的 3 个 KAP 模块与 7 个 VAP 模块，形成 20 个 Maven root/platform 坐标。
3. Prism 使用自身 Gradle Wrapper 与隔离的 SDK Maven 包构建；`releaseStrictDependencies=true` 禁用本机 Maven。主项目不通过 `includeBuild` 或跨仓库 `project` 引用 SDK 源码。
4. [Maven 校验器](<../scripts/verify-maven-publications.py>) 验证 39 个 Prism root/platform 坐标、POM、真实平台源码、元数据跳转、依赖版本、校验和与签名。
5. CLI 在新缓存、独立进程中走[真实合成资源调用](<../scripts/smoke-cli-release.py>)：RGBA8、ASTC、VAP 打包/校验，拒绝旧输入与损坏资源。Windows Runner 再运行同一语料的读取和工具执行。
6. 发布前本地单测、实际 CLI、独立 Maven 消费者和 Android 真机播放验证均须完成。CI 通过不能替代设备验证。

SDK 与 Prism 暂存分开检查，之后合成同一个签名 Central deployment，避免先发布不完整的依赖链。Portal 所需 ZIP 仅是其上传协议载体，不是 CLI 下载分发格式。

## 触发

推送 `main` 或手动运行工作流会构建、测试、签名并保留 Actions 验证产物，但不会上传 Central，也不会创建 Release。

本地与设备验证完成、对应提交的 CI 门禁通过后，给该提交创建与清单一致的 `v<version>` 标签并推送。标签运行才有发布权限：

1. 再次完整构建与验证。
2. [Central 上传器](<../scripts/publish-central-bundle.py>) 上传已校验的原始暂存字节，不重新构建；拒绝已经公开的同版本坐标。
3. 等待 Portal 状态确认为 `PUBLISHED`，不是只看到上传成功或 `VALIDATED`。
4. 只有 Central 成功后，才创建 GitHub 预发布并上传已校验的 CLI 文件。不会将 alpha 设为最新正式版。

尚未发布的版本构建失败时，在同一版本修正重试，不顺手涨号。移动或删除远端标签仍需要明确授权。

## 失败与重试

- 构建/签名/消费验证失败：不上传制品。修复后先重新通过本地和设备验证，再重跑 CI。
- Central 上传后：`central-deployment-id` Artifact 保留部署 UUID，`central-bundle` 保留完全相同的已签名 ZIP 与 SHA-256。不要因超时盲目上传第二遍。
- 选择 **Re-run failed jobs**：工作流必须恢复部署 UUID，才能以 status-only 模式继续等待；恢复失败则关闭发布门禁，不猜测是否上传过，也不重新上传。若之前尚未执行 Central 步骤而没有 UUID，需先确认 Portal 无待处理部署，再针对原标签手动发起新的工作流。不要选择重建全部任务来替代已上传的字节。
- CLI 发行失败但 Central 已成功：库版本已经不可覆盖，只补同一提交的 GitHub 发行步骤。[GitHub 发行器](<../scripts/publish-github-prerelease.py>) 会逐字节核对已经上传的文件，只补 draft 中缺少的文件，不使用覆盖上传；已公开且完整一致的发行视为成功。若有不一致的 draft 或空标签，应明确记录其名称与指向，再按授权清理，不声称发布完成。

Actions 验证产物保留 14 天。设备中转 CSV 在完整验证并回传成功后清理，正式的版本化回归数据继续保留原始测量标签，不因发行版本调整而重标。
