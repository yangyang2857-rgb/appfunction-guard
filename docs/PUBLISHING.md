# 发布到 Maven Central

目标坐标：

```
io.github.yangyang2857:guard-audit:0.1.0-alpha3   # 纯 JVM：审计模型 + CSV/JSON 导出
io.github.yangyang2857:guard-core:0.1.0-alpha3    # Android AAR：拦截链 + 策略 + 审计出口
io.github.yangyang2857:guard-ksp:0.1.0-alpha3     # KSP 处理器（K2 / KSP2 兼容）
io.github.yangyang2857:guard-ui:0.1.0-alpha3      # Android AAR：参考确认弹窗
```

版本号只有一个来源：根 `build.gradle.kts` 的 `allprojects { version = ... }`。改版本只改这一处。

## 已经做好的部分

- 四个模块都应用了 `maven-publish`，POM 里 `name/description/url/licenses/developers/scm` 齐全（Central 的硬性要求）
- Android 模块用 `singleVariant("release") { withSourcesJar() }`，只发布 release AAR + sources
- 每个 artifact 都带 `-sources.jar` 与 `-javadoc.jar`
- `gradle publishAllToLocal` 一条命令装全部模块到 `~/.m2`，CI 已经在校验这件事

本地自查：

```bash
gradle publishAllToLocal
find ~/.m2/repository/io/github/yangyang2857 -name "*.pom" -o -name "*.jar" | sort
```

## 还缺的部分（需要账号与密钥，无法在仓库里替你完成）

1. **Sonatype Central 账号 + 命名空间**
   `io.github.yangyang2857` 通过 GitHub 账号验证即可，不需要域名。首次发布前在
   central.sonatype.com 建 namespace 并验证 `yangyang2857-rgb` 的 GitHub 所有权。

2. **GPG 签名密钥**
   Central 强制要求 `.asc` 签名。生成后：

   ```bash
   gpg --full-generate-key                 # RSA 4096，不过期或长有效期
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> > signing.key
   ```

3. **CI 里的四个 secret**

   | Secret | 用途 |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | Central Portal 的 user token |
   | `SIGNING_KEY` | 上面导出的 ASCII-armored 私钥 |
   | `SIGNING_PASSWORD` | 私钥口令 |

4. **签名与上传的 Gradle 配置**
   在 `gradle/publishing.gradle.kts` 里接上 `signing`，条件是 `SIGNING_KEY` 存在才启用 ——
   这样没有密钥的本地构建不会 fail。上传走 `publishToMavenCentral`（或 nmcp 插件），
   仓库地址不用手写在 build 脚本里。

## 顺序建议

先发 `guard-audit` 与 `guard-ksp`（纯 JVM，没有 Android 变体，最容易过校验），
再发 `guard-core` 与 `guard-ui`。Central 校验失败时错误信息通常在 Portal 的 deployment 页面里，
不在 Gradle 输出里。

## 已知待办

- `-javadoc.jar` 目前是 `docs/JAVADOC.md` 的占位（见该文件），0.2.0 接 Dokka
- 仓库还没有 Gradle Wrapper，CI 用 `gradle/actions/setup-gradle`；README 里已经写明
