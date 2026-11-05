# 发布

## 职责

- GitHub Release 由本人发布，标题只写清单版本号，说明中文在前、英文在后；不使用机器人账号。
- 构建、测试、签名、打包和产物检查全部在本地或发布前完成。
- [CI](<../.github/workflows/release.yml>) 只监听 `release: types: [published]`，只负责把现成制品发布到 Maven Central。推送分支或标签均不触发 CI，不另设手工补发或重建 Release 工作流。
- CI 不创建、修改或删除 GitHub Release，不上传 CLI，不需要 GitHub 个人发布令牌，也不绑定具体用户名。

## 版本与渠道

版本唯一来源为 [gradle.properties](<../gradle.properties>) 的 `version`，Release 标签为 `v<version>`。CLI 横幅、Maven 坐标和标签必须一致；已发布内容不覆盖、不删除、不重新赋予其它字节。

Maven 命名空间为 `com.kaus-io`。客户端使用 `mavenCentral()`，显式指定清单版本。

首次发布保留了 [KAP/VAP 源码快照](<../thirdparty/prism-sdk/PROVENANCE.json>)，包含 Region API 的可追溯来源。后续版本应消费已公开的冻结 SDK 包，不重复上传原 SDK 版本。当前打包器保留首发的坐标白名单；扩大版本或目标支持前，先在本地完成对应验证。

Linux CLI 支持打包和检查；Windows CLI 目前只支持检查，不提供 macOS CLI。CLI 以原生文件分发，不要求下载压缩分发包。内部 JVM 装载器坐标为 `prism-motion-native-loader`，不使用旧的 `utils-jvm`。

## 发布前在本地完成

1. 完成单测、源码快照检查、实际 CLI 调用、独立 Maven 消费者及适用的设备验证。
2. 使用安全的本地签名环境生成所需 Maven 暂存文件，完成 [Maven 产物检查](<../scripts/verify-maven-publications.py>) 和[签名验证](<../scripts/verify-release-signatures.py>)。不把私钥写进仓库或发行文件。
3. 使用[本地打包器](<../scripts/publish-central-bundle.py>) 生成签名制品的原始 Portal 传输包和 SHA-256 文件：

   ```bash
   python3 -B scripts/publish-central-bundle.py bundle \
     --repository "$SDK_STAGE" --repository "$PRISM_STAGE" \
     --output "$RELEASE_STAGE/maven-central-bundle.zip"
   ```

   暂存路径使用操作系统标准临时目录。Portal ZIP 只是 Maven 上传协议的载体，不是 CLI 下载分发格式；不要在 CI 中重新构建或重签。
4. 如需生成 Release 展示文本，可在本地运行[说明准备器](<../scripts/prepare-github-release.py>)；它只校验现成的六个 CLI 文件并生成版本标题、双语说明，不访问远端。
5. 本人选择已验证的标签，在 GitHub 网页上传 CLI 文件，并粘贴版本标题和双语说明。目标 Maven 版本尚未发布时，还须附上 `maven-central-bundle.zip` 和 `maven-central-bundle.zip.sha256`，然后发布 Release；预发布勾选 Pre-release。

修改现有 Release 作者不能直接编辑作者字段。需要本人在网页处理，保留原标签并使用已经备份的原始文件；CI 不提供删除或重建入口。

## CI 的唯一发布步骤

[事件发布器](<../scripts/publish-release-to-central.py>) 读取 Release 事件及根清单版本：

1. 探测 Maven Central 上该版本的核心坐标。确认已经存在时成功退出，不要求重新提供上传包，也不再次上传。
2. 只有明确的 HTTP 404 视为未发布；认证、权限、网络、服务器响应或元数据校验错误照常失败。
3. 新版本从当前 Release 下载已经签名的 Portal 包及 SHA-256 文件，核对原始字节后交给 [Central 上传器](<../scripts/publish-central-bundle.py>)。CI 只上传，不构建、打包或生成说明。
4. 核对标签、checkout 和事件 SHA，一次上传后等待 Portal 确认 `PUBLISHED`。上传前再次确认版本已存在时，也按成功处理，不覆盖已有制品。

CI 只读取已有的 `MAVEN_CENTRAL_USERNAME` 与 `MAVEN_CENTRAL_PASSWORD` Secrets。账号须有 `com.kaus-io` 权限；本地签名所需 GPG 配置不在 CI 中使用。不要在聊天、命令参数、仓库或日志中粘贴密钥和令牌。

## 失败与重试

- 同版本重复发布只有“目标版本明确已经存在”可以成功跳过；其它失败不能吞掉，不使用 `|| true` 或 `continue-on-error`。
- 上传结果不明确或 Portal 仍在处理时，先检查 Portal 中的部署状态和记录的部署 UUID，不盲目上传第二份。
- 已成功发布的版本不因修改 Release 标题、说明或作者而再次生成 Maven 内容。
- 历史标签包含其当时的工作流；本地重写不改变现有线上 Release、标签或已发布制品，也不通过移动标签来更新历史工作流。
