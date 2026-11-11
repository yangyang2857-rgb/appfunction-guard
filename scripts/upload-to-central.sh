#!/usr/bin/env bash
# 把 central-bundle.zip 直接送到 Maven Central（Central Portal Publisher API）。
#
# 前置：
#   1. 在 https://central.sonatype.com/account 生成 User Token（一对 username/password）
#   2. 命名空间 io.github.yangyang2857 已在该账号下验证通过
#   3. bundle 内每个文件都有 .asc 签名，且签名公钥能在 keyserver 上查到
#
# 用法：
#   MAVEN_CENTRAL_USERNAME=xxx MAVEN_CENTRAL_PASSWORD=yyy \
#     ./scripts/upload-to-central.sh            # 上传并自动发布（AUTOMATIC）
#   ... ./scripts/upload-to-central.sh --hold   # 只上传，留给你在网页上点 Publish
#
# 注意：凭据不要写进仓库，用环境变量或 ~/.gradle/gradle.properties 之外的私密文件。
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

BUNDLE=${BUNDLE:-build/distributions/central-bundle.zip}
ENDPOINT="https://central.sonatype.com/api/v1/publisher/upload"
VERSION=$(sed -n 's/.*version = "\([^"]*\)".*/\1/p' build.gradle.kts | head -1)
PUBLISHING_TYPE="AUTOMATIC"

for arg in "$@"; do
  case "$arg" in
    --hold) PUBLISHING_TYPE="USER_MANAGED" ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

: "${MAVEN_CENTRAL_USERNAME:?set MAVEN_CENTRAL_USERNAME (Portal user token name)}"
: "${MAVEN_CENTRAL_PASSWORD:?set MAVEN_CENTRAL_PASSWORD (Portal user token secret)}"
test -f "$BUNDLE" || { echo "missing $BUNDLE (run scripts/make-central-bundle.sh first)" >&2; exit 1; }

TOKEN=$(printf "%s:%s" "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 -w0)
URL="$ENDPOINT?name=appfunction-guard-$VERSION&publishingType=$PUBLISHING_TYPE"

if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "would POST $URL"
  echo "would upload $BUNDLE ($(du -h "$BUNDLE" | cut -f1)) as publishingType=$PUBLISHING_TYPE"
  exit 0
fi

echo "uploading $(du -h "$BUNDLE" | cut -f1) as publishingType=$PUBLISHING_TYPE ..."
RESP=$(curl -sS --fail-with-body -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -F "bundle=@$BUNDLE;type=application/octet-stream" \
  "$URL")

echo "deployment: $RESP"
cat <<EOF

下一步：
  $([ "$PUBLISHING_TYPE" = "AUTOMATIC" ] && echo "已提交自动发布。Portal 会先校验（通常 1-5 分钟），通过后自动 release 到 Central，同步到 search.maven.org 另需 10-30 分钟。" \
     || echo "已上传但未发布。打开 https://central.sonatype.com/publishing/deployments 点 Publish。")
  查看进度：https://central.sonatype.com/publishing/deployments
EOF
