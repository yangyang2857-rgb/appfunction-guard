# 发布到 Maven Central

## 一句话现状

构建、POM 元数据、sources/javadoc、bundle 打包、签名、上传脚本都已经在仓库里，
**剩下的只有账号相关的三件事**，这三件只能由仓库所有者本人操作：

1. 在 Central Portal 认领 `io.github.yangyang2857-rgb` 这个命名空间
2. 生成 User Token（不是登录密码）
3. 把两个 secret 填进仓库

填完之后发版就是点一次 Run workflow。

## 先纠正一个过期认知

Sonatype 的 OSSRH + Nexus staging 流程（`nexus-staging-maven-plugin`、`publishToSonatype`、
`closeAndReleaseSonatypeStagingRepository`、`ossrhUsername/ossrhPassword`）**在 2025 年中已经退役**。
现在全部走 **Central Publisher Portal**（central.sonatype.com）：

| 老流程 | 现在 |
|---|---|
| Nexus staging 仓库 + close/release | Portal deployment（上传一个 zip） |
| `ossrhUsername` / `ossrhPassword`（登录账号） | User Token（Account 页生成，成对出现） |
| `maven-publish` 直推 `s01.oss.sonatype.org` | 产 bundle → `POST /api/v1/publisher/upload` |
| GPG 签名 | 不变，仍然必须 |

所以仓库里**不需要** `gradle-nexus-publish-plugin`，也不需要 `signing` 之外的任何插件。

## 一次性配置（只有三步）

### 1. 认领命名空间

打开 https://central.sonatype.com ，用 **GitHub 账号登录**（就是 `yangyang2857-rgb` 那个）。
登录后 `io.github.yangyang2857-rgb` 会被自动认领 —— GitHub 系命名空间靠 OAuth 身份自动验证，
不需要建临时仓库、不需要 TXT 记录。

在 `Namespaces` 页确认它显示为 `Verified`。

### 2. 生成 User Token

`Account` → `Generate User Token`，会给你两串东西：

```
Username: xxxxxxxx
Password: yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy
```

这不是你的登录密码，可以随时吊销。留着，下一步要用。

### 3. 填 secret 并生成签名密钥

仓库 secret 里最终要有五个（Settings → Secrets and variables → Actions）：

| Secret | 值 |
|---|---|
| `SIGNING_KEY` | GPG 私钥，**armor 文本再 base64 成一行** |
| `SIGNING_PASSWORD` | 该私钥的口令 |
| `GPG_FINGERPRINT` | 密钥指纹，仅备查 |
| `MAVEN_CENTRAL_USERNAME` | 上一步的 Username |
| `MAVEN_CENTRAL_PASSWORD` | 上一步的 Password |

后两个手动填。前三个交给工作流，不用你碰 GPG：

**Actions → `provision-signing-key` → Run workflow**

| 输入 | 填什么 |
|---|---|
| `email` | 默认的 `yangyang2857-rgb@users.noreply.github.com` 就行，不会暴露真实邮箱 |
| `passphrase` | 留空，工作流自己生成 32 位随机串 |
| `admin_token` | 一个有 `repo` 权限的 classic PAT，**只在这次运行里用来写 secret**，跑完可以去撤销 |

它会做三件事：生成 3072 位 RSA 签名密钥 → 把公钥发到 keyserver（Central 校验签名时要去查）
→ 把 `SIGNING_KEY` / `SIGNING_PASSWORD` / `GPG_FINGERPRINT` 写进仓库 secret。

> 为什么不在本地做：本地生成密钥没问题，但把私钥搬到 GitHub 这一步在手机上极难操作，
> 而 runner 到 keyserver 的网络是通的。私钥只出现在 runner 内存和 GitHub 加密存储里，不落日志。

## 发版（每次）

### 常规方式：点一下

`Actions` → `publish-central` → `Run workflow`。它依次做：

```
gradle centralBundle     # 四模块 → build/central-repo → build/distributions/central-bundle.zip
scripts/make-central-bundle.sh   # 校验结构 + 逐个 gpg --verify
scripts/upload-to-central.sh     # POST /api/v1/publisher/upload
```

`hold` 勾上则只上传不发布（Portal 上手动点 Publish）；不勾则 `publishingType=AUTOMATIC`，
Portal 校验通过就自动 release 到 Central。

### 想看产物再手动上传

CI 的 `central-bundle` / `central-bundle-signed` artifact 就是一个 zip，下载后直接拖进
https://central.sonatype.com/publishing/deployments 的上传框。上传框**不接受**这里用一次性密钥
签的包（公钥没上 keyserver），只接受 `publish-central` 用真密钥产出的那个。

### 本地发（有 JDK + Gradle 时）

```bash
export SIGNING_KEY=$(base64 -w0 < my-key.asc)     # armor 文本的 base64
export SIGNING_PASSWORD=口令
./scripts/make-central-bundle.sh

export MAVEN_CENTRAL_USERNAME=... MAVEN_CENTRAL_PASSWORD=...
./scripts/upload-to-central.sh            # 加 --hold 则只上传
```

### 版本号

`build.gradle.kts` 里 `allprojects { version = "0.1.0-alpha3" }` 一处改，四个模块跟着变。
**已发布的版本号不能重发**，Portal 会直接拒（`Version already exists`）。改完记得同步
`gradle/libs.versions.toml` 里对自身的依赖（如果有）和 README 的坐标。

## bundle 里到底有什么

```
io/github/yangyang2857-rgb/guard-core/0.1.0-alpha3/
  guard-core-0.1.0-alpha3.aar
  guard-core-0.1.0-alpha3.aar.asc
  guard-core-0.1.0-alpha3.pom
  guard-core-0.1.0-alpha3.pom.asc
  guard-core-0.1.0-alpha3-sources.jar(.asc)
  guard-core-0.1.0-alpha3-javadoc.jar(.asc)
  guard-core-0.1.0-alpha3.module(.asc)     # Gradle Module Metadata
```

`make-central-bundle.sh` 会逐项检查：目录存在 → pom 有 `<name>/<url>/<licenses>/<developers>/<scm>`
→ 主产物在（JVM 模块 `.jar`，Android 模块 `.aar`）→ sources 与 javadoc 都在 → 每个文件都有 `.asc`
且 `gpg --verify` 通过。任何一项不过就以非零退出。

## 会被 Central 拒的常见原因

| 报错 | 原因 | 对应 |
|---|---|---|
| `Invalid signature` / `No public key` | 公钥没上 keyserver，或 private key 与上传的公钥不配对 | 重跑 `provision-signing-key` |
| `Missing: javadoc` | javadoc jar 是空的占位 | 现在是占位（`gradle/javadoc-stub`），0.2.0 接 Dokka |
| `pom missing licenses` / `developers` / `scm` | POM 缺必填字段 | `make-central-bundle.sh` 会提前拦 |
| `Version already exists` | 版本号复用 | 改 `version` 重发 |
| `Namespace not verified` | 命名空间没认领 | 步骤 1 |
| `401 Unauthorized` | 用了登录密码而不是 User Token | 步骤 2 |
| `Bundle contains unexpected files` | 混进了 `maven-metadata.xml` | 已在打包时排除 |

## javadoc 的债

现在四个模块的 `-javadoc.jar` 是占位：各模块的 `javadocJar` 任务把 `docs/JAVADOC.md`
打进一个 classifier 为 `javadoc` 的 jar。Central **只检查文件存在**，不检查内容，所以能过审。
但用户点进 javadoc 看到的是一个 Markdown 文件而不是 API 文档。0.2.0 接
[Dokka](https://kotlinlang.org/docs/dokka-introduction.html) 之后就是真的文档了。
