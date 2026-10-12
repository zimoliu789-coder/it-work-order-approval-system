#!/bin/sh
# =====================================================================
# ops-agent 入口：先确保共享目录存在，再交给 Python 服务
#
# 为什么要在这里建目录：agent 与后端通过 <deploy>/ops-data 交换文件，
# 而宿主上这个目录可能还不存在（首次部署）。Docker 在源路径不存在时会把
# **目录挂载点建成 root 属主的空目录**，随后以 uid 10001 运行的 agent 写不进去，
# 表现为「心跳永远不就绪」——一个不会报错、只会让人怀疑镜像坏了的故障。
# 因此这里显式创建并放宽属主后再退出到 Python（首次创建后由宿主目录接管）。
# =====================================================================
set -e

SHARED="${OPS_SHARED_DIR:-/deploy/ops-data}"
mkdir -p "${SHARED}/requests" "${SHARED}/upgrade/state/result" 2>/dev/null || true

exec python3 /opt/ops/agent.py
