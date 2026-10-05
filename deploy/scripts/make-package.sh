#!/usr/bin/env bash
# =====================================================================
# 升级包打包器（）
#
# 产出物：一个 zip，结构【必须】是下面这样（包根直接放三样东西，不能多、不能套目录）：
#
#     manifest.json                 清单（version / buildTime / files[]）
#     backend.jar                   后端产物（Spring Boot fat jar）
#     frontend/dist/**              前端构建产物
#
# 为什么结构这么死板：后端 UpgradePackageValidator 是白名单校验，
#   只认 manifest.json / backend.jar / frontend/dist/** 三类条目，
#   多一个文件、少一层目录、或者条目名带上 "./" 前缀，都会被直接拒绝。
#   与其让打包的人事后对着「升级包内含有不允许的文件：./backend.jar」这类报错
#   猜原因，不如在这里就把结构钉死，并且打完后【自己再解开查一遍】（见 self_check）。
#
# 用法：
#   bash make-package.sh <version> [<outputZip>]
#
#   bash make-package.sh 1.5.0
#       → deploy/packages/ticket-1.5.0.zip
#   bash make-package.sh 1.5.0 /tmp/ticket-1.5.0.zip
#
# 环境变量：
#   JAR_SRC        后端 jar 路径（默认自动在 backend/target 下挑最大的那个 .jar）
#   DIST_SRC       前端产物目录（默认 frontend/dist）
#   OUT_DIR        未指定 outputZip 时的输出目录（默认 deploy/packages）
#   PYTHON_BIN     zip 缺失时的降级实现所用解释器（默认自动探测 python3 / python）
#
# 退出码：0 = 打包成功且自检通过；非 0 = 失败（失败时不会留下半成品 zip）
#
# ⚠️ 关于版本号：必须匹配 ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$
#    与后端 UpgradePackageValidator.VERSION_PATTERN 完全一致。
#    之所以不允许空格 / 斜杠 / 引号：版本号会被外部 shell 脚本拼进路径与参数，
#    允许任意字符等于把注入面交给打包方。
# =====================================================================
set -euo pipefail

VERSION="${1:-}"
OUTPUT_ZIP="${2:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

MANIFEST_NAME="manifest.json"
BACKEND_JAR_NAME="backend.jar"
FRONTEND_DIST_DIR="frontend/dist"

JAR_SRC="${JAR_SRC:-}"
DIST_SRC="${DIST_SRC:-${ROOT_DIR}/frontend/dist}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/deploy/packages}"
PYTHON_BIN="${PYTHON_BIN:-}"

TMP_DIR=""

log() { echo "[make-package] $*"; }
die() { echo "[make-package] ✗ $*" >&2; exit 1; }

cleanup() {
  [ -n "${TMP_DIR}" ] && [ -d "${TMP_DIR}" ] && rm -rf "${TMP_DIR}"
  return 0
}
trap cleanup EXIT

# ---------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------
preflight() {
  [ -n "${VERSION}" ] || die "缺少参数 version。用法：bash make-package.sh <version> [<outputZip>]"

  if ! printf '%s' "${VERSION}" | grep -Eq '^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$'; then
    die "版本号不合法：'${VERSION}'。必须匹配 ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$（字母数字开头，仅含 . _ -，总长 1~64）"
  fi

  # 后端 jar：默认在 target 下挑【最大的】那个。
  # 理由：Spring Boot repackage 后 fat jar 明显大于原始的 thin jar，
  # 且同一目录下常有 *-sources.jar / *-javadoc.jar 这类小文件干扰。
  if [ -z "${JAR_SRC}" ]; then
    local candidates best best_size=-1 f sz
    candidates="$(find "${ROOT_DIR}/backend/target" -maxdepth 1 -type f -name '*.jar' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort || true)"
    [ -n "${candidates}" ] || die "在 backend/target 下找不到 .jar，请先执行：cd backend && mvn -DskipTests package"
    while IFS= read -r f; do
      [ -n "${f}" ] || continue
      sz="$(wc -c < "${f}" | tr -d ' ')"
      if [ "${sz}" -gt "${best_size}" ]; then best_size="${sz}"; best="${f}"; fi
    done <<< "${candidates}"
    JAR_SRC="${best}"
    log "自动选中后端 jar：${JAR_SRC}"
    log "  （候选：$(printf '%s' "${candidates}" | tr '\n' ' '))"
  fi

  [ -f "${JAR_SRC}" ] || die "后端 jar 不存在：${JAR_SRC}"
  [ -d "${DIST_SRC}" ] || die "前端产物目录不存在：${DIST_SRC}（请先执行：cd frontend && npm run build）"

  if [ -z "$(ls -A "${DIST_SRC}" 2>/dev/null)" ]; then
    die "前端产物目录为空：${DIST_SRC}。空的 dist 打出来的包会让升级后的前端 404"
  fi
  [ -f "${DIST_SRC}/index.html" ] || log "  ⚠ ${DIST_SRC} 下没有 index.html，确认这是完整的构建产物"

  if [ -n "${OUTPUT_ZIP}" ]; then
    OUT_DIR="$(cd "$(dirname "${OUTPUT_ZIP}")" && pwd)" || die "输出目录不存在：$(dirname "${OUTPUT_ZIP}")"
    OUTPUT_ZIP="${OUT_DIR}/$(basename "${OUTPUT_ZIP}")"
  else
    mkdir -p "${OUT_DIR}"
    OUTPUT_ZIP="${OUT_DIR}/ticket-${VERSION}.zip"
  fi
}

# ---------------------------------------------------------------------
# 组装 staging（临时目录），结构与包内结构一一对应
# ---------------------------------------------------------------------
stage() {
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ticket-pkg-XXXXXX")" || die "无法创建临时目录"
  log "临时工作目录：${TMP_DIR}"

  # 统一改名成 backend.jar —— 包内文件名是固定的，与构建产物的实际文件名无关。
  # 这样「下游脚本/文档里写死 backend.jar」这件事才是成立的。
  cp "${JAR_SRC}" "${TMP_DIR}/${BACKEND_JAR_NAME}"
  mkdir -p "${TMP_DIR}/${FRONTEND_DIST_DIR}"
  cp -a "${DIST_SRC}/." "${TMP_DIR}/${FRONTEND_DIST_DIR}/"

  log "已组装：backend.jar ($(human_size "$(wc -c < "${TMP_DIR}/${BACKEND_JAR_NAME}")")) + ${FRONTEND_DIST_DIR} ($(find "${TMP_DIR}/${FRONTEND_DIST_DIR}" -type f | wc -l | tr -d ' ') 个文件)"
}

human_size() {
  awk -v b="$1" 'BEGIN{
    if (b >= 1048576) printf "%.1fMB", b/1048576;
    else if (b >= 1024) printf "%.1fKB", b/1024;
    else printf "%dB", b;
  }'
}

sha256_of() {
  local out
  # 【必须从标准输入读】，不能把文件名当参数传给 sha256sum：
  # 文件名里只要含反斜杠（Windows 上 mktemp -d 返回的就是 C:\Users\... 这种路径），
  # GNU coreutils 就会在输出行首加一个 "\" 转义标记，
  # 于是 cut -d' ' -f1 得到的是 "\f4962afc…" 这种【带前缀的假哈希】。
  # 这种包打出来看着完全正常，上传后却被后端判为「校验和不匹配」——
  # 而那句报错完全指不到打包脚本这一行。走 stdin 时文件名显示为 "-"，不会触发转义。
  if command -v sha256sum >/dev/null 2>&1; then
    out="$(sha256sum < "$1" | cut -d' ' -f1)"
  elif command -v shasum >/dev/null 2>&1; then
    out="$(shasum -a 256 < "$1" | cut -d' ' -f1)"
  else
    die "找不到 sha256sum / shasum，无法计算校验和"
  fi

  # 兜底断言：不是 64 位小写十六进制就当场失败，绝不把坏哈希写进 manifest。
  # 这条检查的成本是一次 grep，收益是「把一次 60MB 上传之后才会暴露的失败」
  # 提前到打包机上暴露。
  if ! printf '%s' "${out}" | grep -Eq '^[0-9a-f]{64}$'; then
    die "计算出的 SHA-256 格式异常：'${out}'（应为 64 位小写十六进制）。文件：$1"
  fi
  printf '%s' "${out}"
}

# ---------------------------------------------------------------------
# 生成 manifest.json
#
# files[] 必须【恰好】覆盖包内除 manifest.json 之外的全部文件 ——
# 后端做的是双向一致性校验：声明了却没有 → 拒绝；有却没声明 → 也拒绝。
# 所以要遍历真实目录生成，不能手写条目。
# ---------------------------------------------------------------------
gen_manifest() {
  local rel sha size first=1
  local manifest="${TMP_DIR}/${MANIFEST_NAME}"
  local build_time
  build_time="$(date '+%Y-%m-%dT%H:%M:%S%z')"

  {
    printf '{\n'
    printf '  "version": "%s",\n' "${VERSION}"
    printf '  "buildTime": "%s",\n' "${build_time}"
    printf '  "files": [\n'
    # 相对路径一律用 / 分隔：zip 规范如此，后端的路径安全检查也会拒绝反斜杠
    while IFS= read -r rel; do
      [ -n "${rel}" ] || continue
      [ "${rel}" = "${MANIFEST_NAME}" ] && continue
      sha="$(sha256_of "${TMP_DIR}/${rel}")"
      size="$(wc -c < "${TMP_DIR}/${rel}" | tr -d ' ')"
      [ "${first}" -eq 1 ] || printf ',\n'
      printf '    {"path": "%s", "sha256": "%s", "size": %s}' "${rel}" "${sha}" "${size}"
      first=0
    done < <(cd "${TMP_DIR}" && find . -type f -print | sed 's|^\./||' | LC_ALL=C sort)
    printf '\n  ]\n'
    printf '}\n'
  } > "${manifest}"

  local count
  count="$(find "${TMP_DIR}" -type f ! -name "${MANIFEST_NAME}" | wc -l | tr -d ' ')"
  log "已生成 manifest.json：version=${VERSION} files=${count}"
}

# ---------------------------------------------------------------------
# 打 zip
# ---------------------------------------------------------------------
detect_python() {
  if [ -n "${PYTHON_BIN}" ]; then return 0; fi
  local c
  for c in python3 python; do
    if command -v "${c}" >/dev/null 2>&1; then PYTHON_BIN="${c}"; return 0; fi
  done
  return 1
}

create_zip() {
  rm -f "${OUTPUT_ZIP}"

  if command -v zip >/dev/null 2>&1; then
    # 必须【显式列出顶层条目】，不能用 `zip -r out.zip .`：
    # 后者会生成 "./backend.jar" 这样的条目名，带上了 "./" 前缀，
    # 而后端白名单是精确匹配 "backend.jar" → 整个包被判非法。
    # 这是最容易踩、且报错信息最不直观的一个坑，所以直接避开。
    ( cd "${TMP_DIR}" && zip -r -X -q "${OUTPUT_ZIP}" "${MANIFEST_NAME}" "${BACKEND_JAR_NAME}" "frontend" )
    log "已用 zip 命令生成升级包"
    return 0
  fi

  if ! detect_python; then
    die "既没有 zip 命令也没有 python3，无法生成升级包。请安装 zip（Debian/Ubuntu: apt install zip）"
  fi
  log "  ⚠ 未找到 zip 命令，降级使用 ${PYTHON_BIN} zipfile 生成"

  # os.walk + 相对路径：条目名天然是 backend.jar / frontend/dist/xxx，
  # 既没有 "./" 前缀，也会自动跳过目录条目
  "${PYTHON_BIN}" - "${TMP_DIR}" "${OUTPUT_ZIP}" <<'PY'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
count = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            # 固定时间戳，让同样输入产出同样的字节（可复现打包，便于比对哈希）
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, "rb") as fh:
                zf.writestr(info, fh.read())
            count += 1
print("  写入条目数：%d" % count)
PY
}

# ---------------------------------------------------------------------
# 自检：把刚打出来的包解开看一遍，结构必须与校验器口径完全一致
#
# 这一步的价值在于：把「上传后才发现的拒绝」提前到打包机上暴露。
# 后端那道闸门每次拒绝都要等一次几百 MB 的上传，而这里的检查是零成本的。
# ---------------------------------------------------------------------
zip_list() {
  if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$1"
    return 0
  fi
  if detect_python; then
    "${PYTHON_BIN}" - "$1" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as zf:
    for name in zf.namelist():
        print(name)
PY
    return 0
  fi
  return 1
}

self_check() {
  log "自检：核对包内条目与后端白名单"
  local entries
  if ! entries="$(zip_list "${OUTPUT_ZIP}")"; then
    log "  ⚠ 既没有 unzip 也没有 python3，跳过条目自检（后端仍会校验）"
    return 0
  fi

  local bad=0 total=0 manifest_seen=0 jar_seen=0 dist_seen=0 name
  while IFS= read -r name; do
    [ -n "${name}" ] || continue
    total=$(( total + 1 ))
    case "${name}" in
      "${MANIFEST_NAME}")          manifest_seen=1 ;;
      "${BACKEND_JAR_NAME}")       jar_seen=1 ;;
      "${FRONTEND_DIST_DIR}"/*)    dist_seen=1 ;;
      *)
        log "  ✗ 不被允许的条目：${name}"
        bad=$(( bad + 1 ))
        ;;
    esac
  done <<< "${entries}"

  [ "${manifest_seen}" -eq 1 ] || { log "  ✗ 缺少 ${MANIFEST_NAME}"; bad=$(( bad + 1 )); }
  [ "${jar_seen}" -eq 1 ] || { log "  ✗ 缺少 ${BACKEND_JAR_NAME}"; bad=$(( bad + 1 )); }
  [ "${dist_seen}" -eq 1 ] || { log "  ✗ 缺少 ${FRONTEND_DIST_DIR}/ 下的文件"; bad=$(( bad + 1 )); }

  if [ "${bad}" -gt 0 ]; then
    rm -f "${OUTPUT_ZIP}"
    die "自检未通过（${bad} 项问题），已删除半成品包 ${OUTPUT_ZIP}"
  fi
  log "  ✓ 条目合计 ${total} 个，全部在白名单内"
}

# ---------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------
main() {
  log "== 打包开始 version=${VERSION} =="
  preflight
  stage
  gen_manifest
  create_zip
  self_check

  local pkg_size pkg_sha
  pkg_size="$(wc -c < "${OUTPUT_ZIP}" | tr -d ' ')"
  pkg_sha="$(sha256_of "${OUTPUT_ZIP}")"

  echo
  log "== 打包完成 =="
  echo "  包路径    : ${OUTPUT_ZIP}"
  echo "  包大小    : $(human_size "${pkg_size}")"
  echo "  包 SHA-256: ${pkg_sha}"
  echo "  目标版本  : ${VERSION}"
  echo
  echo "  页面上传后，把「包 SHA-256」与上面这行逐字符比对：不一致就说明上传过程出了问题，"
  echo "  不要点「立即应用」——换一个包重传，而不是重试同一个包。"
}

main
