#!/usr/bin/env bash
# 在 dsh-go 的 Ubuntu（PRoot，arm64 Linux）里跑 Gemini Web 后端，
# 让手机的 magpie 用「连接已有后端」接上它。
#
#   bash gemini-web-backend.sh            # 首次安装并启动
#   bash gemini-web-backend.sh status     # 看状态
#   bash gemini-web-backend.sh stop       # 停
#   bash gemini-web-backend.sh log        # 看日志
#
# 为什么不在手机 App 里跑：这个后端是 linux/arm64 的 ET_EXEC（非 PIE），
# Android 只肯执行 PIE，而且只允许从 APK 的 lib/ 目录执行 —— 后者在安装后只读。
# dsh-go 的 Ubuntu 是普通 Linux，没这两条限制，且 PRoot 与宿主共享 127.0.0.1，
# 所以 magpie 能直接连到它。
set -euo pipefail

VER="v6.5.0"
NAME="gemini-web2api-go_${VER}_linux_arm64"
DIR="${GEMINI_WEB_DIR:-$HOME/.gemini-web}"
PORT="${GEMINI_WEB_PORT:-8083}"
KEY="${GEMINI_WEB_KEY:-}"
BIN="$DIR/gemini-web2api-go"
LOG="$DIR/backend.log"
CONF="$DIR/config.json"

mkdir -p "$DIR/data"

info() { printf '\033[36m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[31m!!\033[0m %s\n' "$*" >&2; exit 1; }

gen_key() {
    # 只用字母数字，避免 URL/JSON 转义问题
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 16
    else
        head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' | cut -c1-32
    fi
}

start() {
    [ -x "$BIN" ] || die "还没装：先跑 bash $(basename "$0") install"
    if curl -s -m 2 -o /dev/null "http://127.0.0.1:$PORT/v1/models"; then
        info "已经在跑（127.0.0.1:$PORT）"
        return 0
    fi
    info "启动后端 …"
    cd "$DIR"
    setsid nohup "$BIN" --config "$CONF" --api-key "$KEY" \
        --db "$DIR/data/gemini.db" >>"$LOG" 2>&1 &
    for _ in $(seq 1 40); do
        sleep 0.5
        if curl -s -m 1 -o /dev/null "http://127.0.0.1:$PORT/v1/models"; then
            info "就绪"
            summary
            return 0
        fi
    done
    die "起不来，看日志：$LOG"
}

summary() {
    local token
    token="$(sed -n 's/.*"admin_token": *"\([^"]*\)".*/\1/p' "$CONF" 2>/dev/null | head -1)"
    cat <<EOF

给 magpie 的供应商填这两个值（供应商 → Gemini Web → 连接已有后端）：

  地址    http://127.0.0.1:$PORT/v1
  API Key $KEY

后端管理面板（第一次登录 Google 用）：
  http://127.0.0.1:$PORT/admin
  admin token: ${token:-见 $CONF}

日志：$LOG
EOF
}

case "${1:-install}" in
install)
    if [ -z "$KEY" ]; then
        KEY="$(gen_key)"
        info "生成本次使用的 API Key：$KEY"
    fi
    if [ ! -x "$BIN" ]; then
        info "下载 $NAME（约 17 MB）…"
        ok=0
        for base in "https://gh-proxy.com/https://github.com" \
                    "https://ghfast.top/https://github.com" \
                    "https://github.com"; do
            url="$base/dorokuma/gemini-web2api-go/releases/download/$VER/$NAME"
            info "  试 $base"
            if curl -fL --connect-timeout 15 -m 600 -o "$BIN.part" "$url"; then
                mv "$BIN.part" "$BIN"; chmod +x "$BIN"; ok=1; break
            fi
            rm -f "$BIN.part"
        done
        [ "$ok" = 1 ] || die "下载失败；可以挂代理后用官方 install 脚本，或换网络再试"
    fi
    # 固定监听回环，token 写进配置，端口来自环境变量
    cat > "$CONF" <<JSON
{
  "port": $PORT,
  "host": "127.0.0.1",
  "db_path": "$DIR/data/gemini.db",
  "admin_enabled": true,
  "admin_token": "$(gen_key)",
  "log_requests": false,
  "browser_transport": false
}
JSON
    start
    ;;
start)  start ;;
stop)
    pkill -f 'gemini-web2api-go --config' 2>/dev/null && info "已停止" || info "本来就没在跑"
    ;;
status)
    if curl -s -m 2 -o /dev/null "http://127.0.0.1:$PORT/v1/models"; then
        info "运行中：127.0.0.1:$PORT"; summary
    else
        info "没在跑"
    fi
    ;;
log) tail -n 60 "$LOG" 2>/dev/null || echo "还没有日志" ;;
*) die "用法：$0 [install|start|stop|status|log]" ;;
esac
