#!/usr/bin/env bash
# 生成 Maven Central Portal 上传包，并做一次"能不能过审"的静态检查。
#
# 用法：
#   ./scripts/make-central-bundle.sh                 # 用环境里的 SIGNING_KEY 签名（没有就只打包）
#   ./scripts/make-central-bundle.sh --ephemeral-key # 现场生成一次性密钥，仅用于验证链路
#   ./scripts/make-central-bundle.sh --verify-only   # 只校验已有的 bundle
#
# 环境变量：
#   SIGNING_KEY       ASCII-armored 私钥，base64 编码后的一行字符串
#   SIGNING_PASSWORD  私钥口令（无口令留空）
#
# 签名方式：脚本自己用 gpg 对暂存目录里每个文件做分离签名（--detach-sign --armor），
# 不走 Gradle 的 signing 插件——Gradle 内置 BouncyCastle 对部分 gpg 版本导出的私钥
# 会报 "Could not read PGP secret key"，用它签会卡在这里，而 Central 只认 .asc 文件本身。
#
# 产物：build/distributions/central-bundle.zip
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

MODULES="guard-audit guard-core guard-ksp guard-ui"
VERSION=$(sed -n 's/.*version = "\([^"]*\)".*/\1/p' build.gradle.kts | head -1)
GROUP_PATH="io/github/yangyang2857-rgb"
BUNDLE="build/distributions/central-bundle.zip"
STAGE="build/central-repo"

EPHEMERAL=0
VERIFY_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --ephemeral-key) EPHEMERAL=1 ;;
    --verify-only) VERIFY_ONLY=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [ -z "${VERSION:-}" ]; then
  echo "cannot read version from build.gradle.kts" >&2
  exit 1
fi
echo "version: $VERSION"

# --------------------------------------------------------------------------
# 1. 密钥
# --------------------------------------------------------------------------
if [ "$VERIFY_ONLY" -eq 0 ] && [ -z "${SIGNING_KEY:-}" ] && [ "$EPHEMERAL" -eq 1 ]; then
  echo "generating an ephemeral signing key (CI only, never publish with it)"
  GNUPGHOME=$(mktemp -d)
  export GNUPGHOME
  chmod 700 "$GNUPGHOME"
  cat > "$GNUPGHOME/batch" <<'EOF'
%no-protection
Key-Type: RSA
Key-Length: 3072
Name-Real: AppFunction Guard CI
Name-Email: ci@afguard.invalid
Expire-Date: 1d
%commit
EOF
  gpg --batch --gen-key "$GNUPGHOME/batch" >/dev/null 2>&1
  SIGNING_KEY=$(gpg --armor --export-secret-keys ci@afguard.invalid | base64 -w0)
  export SIGNING_KEY
  SIGNING_PASSWORD=""
  export SIGNING_PASSWORD
  echo "ephemeral key ready (fingerprint: $(gpg --list-keys --with-colons ci@afguard.invalid | awk -F: '/^fpr/{print $10; exit}'))"
fi

# --------------------------------------------------------------------------
# 2. 打包
# --------------------------------------------------------------------------
if [ "$VERIFY_ONLY" -eq 0 ]; then
  GRADLE_ARGS=(centralBundle --no-daemon --stacktrace)
  # 注意：这里故意不把私钥交给 Gradle。Gradle 内置的 BouncyCastle 读不了某些 gpg 版本
  # 导出的私钥（报 "Could not read PGP secret key"），签名统一放到 2.5 步用 gpg 做分离签名，
  # 既绕开兼容问题，也让校验环节能直接用 gpg --verify 验签。
  if [ -z "${SIGNING_KEY:-}" ]; then
    echo "WARNING: SIGNING_KEY is empty — the bundle will lack .asc files and Central will reject it."
  fi
  # 关键：必须把密钥从子进程环境里摘掉。根 build.gradle.kts 会从 env SIGNING_KEY
  # 推断需要签名，而 Gradle 内置 BouncyCastle 读不了 gpg 导出的私钥
  # （"secret key ring doesn't start with secret key tag"）。签名统一放到 2.5 步做。
  if command -v gradle >/dev/null 2>&1; then
    env -u SIGNING_KEY -u SIGNING_PASSWORD gradle "${GRADLE_ARGS[@]}"
  else
    env -u SIGNING_KEY -u SIGNING_PASSWORD ./gradlew "${GRADLE_ARGS[@]}"
  fi
fi

# --------------------------------------------------------------------------
# 2.5 分离签名 + 重新打包
# --------------------------------------------------------------------------
if [ "$VERIFY_ONLY" -eq 0 ] && [ -n "${SIGNING_KEY:-}" ]; then
  if [ -z "${GNUPGHOME:-}" ] || [ -z "$(gpg --list-secret-keys 2>/dev/null)" ]; then
    GNUPGHOME=$(mktemp -d)
    export GNUPGHOME
    chmod 700 "$GNUPGHOME"
    printf '%s' "$SIGNING_KEY" | base64 -d | gpg --batch --quiet --import
  fi

  echo "signing artifacts with gpg ..."
  SIGNED_FILES=0
  while IFS= read -r -d '' F; do
    case "$F" in *.asc) continue ;; esac
    case "$F" in *maven-metadata.xml*) continue ;; esac
    if [ -n "${SIGNING_PASSWORD:-}" ]; then
      gpg --batch --yes --armor --detach-sign --pinentry-mode loopback \
        --passphrase "$SIGNING_PASSWORD" -o "$F.asc" "$F"
    else
      gpg --batch --yes --armor --detach-sign -o "$F.asc" "$F"
    fi
    SIGNED_FILES=$((SIGNED_FILES + 1))
  done < <(find "$STAGE" -type f -print0)
  echo "signed $SIGNED_FILES files"

  rm -f "$BUNDLE"
  mkdir -p "$(dirname "$BUNDLE")"
  ( cd "$STAGE" && zip -qry "$REPO_ROOT/$BUNDLE" . -x "io/github/yangyang2857-rgb/*/maven-metadata.xml*" )
  echo "rebuilt bundle with .asc files"
fi

# --------------------------------------------------------------------------
# 3. 校验
# --------------------------------------------------------------------------
test -f "$BUNDLE" || { echo "missing $BUNDLE" >&2; exit 1; }

WORK=$(mktemp -d)
unzip -q "$BUNDLE" -d "$WORK"
FAIL=0
SIGNED=0

for M in $MODULES; do
  D="$WORK/$GROUP_PATH/$M/$VERSION"
  echo "--- $M ---"
  if [ ! -d "$D" ]; then echo "  missing directory"; FAIL=1; continue; fi

  ls -1 "$D" | sed 's/^/  /'

  # pom 必须有
  test -f "$D/$M-$VERSION.pom" || { echo "  missing pom"; FAIL=1; }

  # 主产物：JVM 模块是 .jar，Android 模块是 .aar
  if [ -f "$D/$M-$VERSION.jar" ] || [ -f "$D/$M-$VERSION.aar" ]; then :; else
    echo "  missing main artifact (.jar/.aar)"; FAIL=1
  fi

  # sources / javadoc 是 Central 的硬要求
  test -f "$D/$M-$VERSION-sources.jar" || { echo "  missing sources jar"; FAIL=1; }
  test -f "$D/$M-$VERSION-javadoc.jar" || { echo "  missing javadoc jar"; FAIL=1; }

  # POM 里的必填项
  if [ -f "$D/$M-$VERSION.pom" ]; then
    for KEY in "<name>" "<url>" "<licenses>" "<developers>" "<scm>"; do
      grep -q "$KEY" "$D/$M-$VERSION.pom" || { echo "  pom missing $KEY"; FAIL=1; }
    done
  fi

  # 签名：每个文件都要有同名 .asc
  for F in "$D"/*; do
    case "$F" in *.asc) continue ;; esac
    case "$F" in *maven-metadata.xml*) continue ;; esac
    if [ -f "$F.asc" ]; then
      SIGNED=$((SIGNED + 1))
      if command -v gpg >/dev/null 2>&1; then
        gpg --verify "$F.asc" "$F" >/dev/null 2>&1 || { echo "  bad signature: $(basename "$F")"; FAIL=1; }
      fi
    else
      echo "  unsigned: $(basename "$F")"
      [ -n "${SIGNING_KEY:-}" ] && FAIL=1
    fi
  done
done

echo "---"
echo "signed artifacts: $SIGNED"
echo "bundle size: $(du -h "$BUNDLE" | cut -f1)"

if [ "$FAIL" -ne 0 ]; then
  echo "BUNDLE CHECK FAILED" >&2
  exit 1
fi
echo "BUNDLE CHECK OK: $BUNDLE"
