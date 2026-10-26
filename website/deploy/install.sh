#!/usr/bin/env bash
# 拾词官网 H2 首次部署脚本。
#
# 用途：在已上传的 bundle 目录内，由 root 手动执行一次：
#     bash install.sh
#
# bundle 需包含（由父侧打包）：
#     index.html styles.css content.js app.js nginx.conf SHA256SUMS
# SHA256SUMS 必须恰好覆盖上述五个文件，不接受其他文件名。
#
# 本脚本执行的动作：
#   1. 严格前置检查（root、bundle 完整性、哈希、无已有 nginx/apache/caddy、
#      无已有 nginx systemd 单元/抑制目录、TCP 80 空闲、目标路径不存在）。
#   2. 安装前先建立仅针对 nginx.service 的可恢复启动抑制（systemd drop-in
#      + 标记文件），确保 apt 装包期间不会自动公开标准 default 站。
#   3. 通过 apt 安装本批已审版本 nginx 与 nginx-common 1.24.0-2ubuntu7.18
#      （--no-install-recommends；版本不可用即失败，不降级、不替换）。
#   4. 只移除本次 apt 新装且经包归属校验的标准 default 站点链接。
#   5. 创建 /srv/shici-website/www，只复制四个静态文件。
#   6. 安装 /etc/nginx/sites-available/shici-website 与唯一启用链接。
#   7. nginx -t 通过后先解除启动抑制，再 start/reload 并 enable 开机自启；
#      这是本批 nginx 首次可能对公网提供页面的时刻。
#
# 本脚本不做：外网 HTTP 测试、DNS/域名、HTTPS/443、防火墙/云安全组、
# APK/仓库/模型/词库/学习数据发布；不读取任何密钥或代理凭据。
# 任何一步失败均以非 0 退出；失败时只回退本批创建的对象，不卸载软件包，
# 并保证本批新引入的 nginx 服务不运行、不自启（无法确认时保留启动抑制）。

set -Eeuo pipefail

export LC_ALL=C
export DEBIAN_FRONTEND=noninteractive
umask 022

readonly SITE_NAME="shici-website"
readonly SITE_ROOT="/srv/shici-website"
readonly WWW_ROOT="${SITE_ROOT}/www"
readonly NGINX_AVAILABLE="/etc/nginx/sites-available/${SITE_NAME}"
readonly NGINX_ENABLED="/etc/nginx/sites-enabled/${SITE_NAME}"
readonly DEFAULT_AVAILABLE="/etc/nginx/sites-available/default"
readonly DEFAULT_ENABLED="/etc/nginx/sites-enabled/default"
readonly SUMS_FILE="SHA256SUMS"

# 仅作用于本批全新 nginx.service 的启动抑制：
#   - 目录/文件只允许本脚本创建，部署成功后删除；
#   - 标记文件存在时，ConditionPathExists=!... 使 nginx.service 被跳过；
#   - 不写 policy-rc.d、不 mask 单元、不改全局启动策略、不覆盖已有 systemd 对象。
readonly SUPPRESS_DIR="/etc/systemd/system/nginx.service.d"
readonly SUPPRESS_CONF_NAME="10-shici-website-deploy-hold.conf"
readonly SUPPRESS_HOLD_NAME="shici-website-deploy.hold"
readonly SUPPRESS_CONF="${SUPPRESS_DIR}/${SUPPRESS_CONF_NAME}"
readonly SUPPRESS_HOLD="${SUPPRESS_DIR}/${SUPPRESS_HOLD_NAME}"

# 本次首部署已审的 Ubuntu 24.04 软件包版本：安装时固定，不可用即失败，
# 不降级、不替换。这是本批的版本锁定，不是永久更新策略。
readonly NGINX_PKG_VERSION="1.24.0-2ubuntu7.18"

STATIC_FILES=(index.html styles.css content.js app.js)
BUNDLE_FILES=(index.html styles.css content.js app.js nginx.conf)

SUPPRESS_CONF_LINES=(
    '# 由拾词官网 install.sh 在本批首次部署期间创建，用于阻止本批新装 nginx'
    '# 在被显式放行前启动；部署成功后删除本文件与 shici-website-deploy.hold。'
    '[Unit]'
    'ConditionPathExists=!/etc/systemd/system/nginx.service.d/shici-website-deploy.hold'
)
SUPPRESS_CONF_HASH="$(printf '%s\n' "${SUPPRESS_CONF_LINES[@]}" | sha256sum | awk '{print $1}')"

BUNDLE_DIR="$(pwd -P)"

SUCCESS=0
INSTALL_ATTEMPTED=0
CREATED_SITE_ROOT=0
CREATED_WWW=0
INSTALLED_CONF=0
CREATED_LINK=0
REMOVED_DEFAULT_LINK=0
SUPPRESSION_CREATED=0
CREATED_LINK_ID=""
declare -A EXPECTED_HASH=()

log()  { printf '[%s] %s\n' "$1" "$2"; }
info() { log "INFO" "$*"; }
ok()   { log "OK" "$*"; }
warn() { log "WARN" "$*" >&2; }

die() {
    log "ERROR" "$*" >&2
    exit 1
}

cleanup() {
    local status=$?
    local f service_safe=0

    if [ "$SUCCESS" -eq 1 ]; then
        return 0
    fi

    set +e
    warn "部署未完成，开始回退本批创建的对象（不卸载软件包，不触碰 443/其他服务）。"

    # 1. 先停止并取消本批新引入 nginx 的自启。使用实际安装结果判断，
    #    不依赖“安装成功”标记：apt 部分失败时该标记可能仍为 0。
    if stop_and_disable_batch_nginx; then
        service_safe=1
    else
        warn "无法确认本批 nginx 已停止且未自启；将保留/重建启动抑制，请人工检查。"
    fi

    # 2. 只移除本批创建、且仍指向本批配置的启用链接（并核对创建时 inode）。
    if [ "$CREATED_LINK" -eq 1 ]; then
        if [ ! -e "$NGINX_ENABLED" ] && [ ! -L "$NGINX_ENABLED" ]; then
            :
        elif enabled_link_is_ours; then
            rm -f -- "$NGINX_ENABLED" && info "已移除本批启用链接：$NGINX_ENABLED" \
                || warn "删除启用链接失败，请人工检查：$NGINX_ENABLED"
        else
            warn "启用链接已非本批创建的对象或已变化，保留不动：$NGINX_ENABLED"
        fi
    elif [ -e "$NGINX_ENABLED" ] || [ -L "$NGINX_ENABLED" ]; then
        warn "发现同路径启用链接但无法确认归属，保留不动：$NGINX_ENABLED"
    fi

    # 3. 只移除哈希仍等于父侧 SHA256SUMS 的本批配置。
    if [ "$INSTALLED_CONF" -eq 1 ]; then
        if [ -f "$NGINX_AVAILABLE" ] && [ ! -L "$NGINX_AVAILABLE" ] \
            && [ -n "${EXPECTED_HASH[nginx.conf]:-}" ] \
            && [ "$(sha256sum -- "$NGINX_AVAILABLE" 2>/dev/null | awk '{print $1}')" = "${EXPECTED_HASH[nginx.conf]}" ]; then
            rm -f -- "$NGINX_AVAILABLE" && info "已移除本批站点配置：$NGINX_AVAILABLE"
        else
            warn "站点配置哈希与本批不一致或路径已变化，保留不动：$NGINX_AVAILABLE"
        fi
    elif [ -e "$NGINX_AVAILABLE" ] || [ -L "$NGINX_AVAILABLE" ]; then
        warn "发现同路径站点配置但无法确认归属，保留不动：$NGINX_AVAILABLE"
    fi

    # 4. 只清理本批创建的 www 中四个公开文件：逐文件哈希匹配本批内容才删除，
    #    内容不匹配（可能被并发改动）或路径已非本批常规文件的一律保留并告警。
    if [ "$CREATED_WWW" -eq 1 ] && [ -d "$WWW_ROOT" ] && [ ! -L "$WWW_ROOT" ]; then
        for f in "${STATIC_FILES[@]}"; do
            if [ ! -e "$WWW_ROOT/$f" ] && [ ! -L "$WWW_ROOT/$f" ]; then
                continue
            fi
            if [ -L "$WWW_ROOT/$f" ] || [ ! -f "$WWW_ROOT/$f" ]; then
                warn "静态路径不是常规文件，保留不动：$WWW_ROOT/$f"
                continue
            fi
            if [ -n "${EXPECTED_HASH[$f]:-}" ] \
                && [ "$(sha256sum -- "$WWW_ROOT/$f" 2>/dev/null | awk '{print $1}')" = "${EXPECTED_HASH[$f]}" ]; then
                rm -f -- "$WWW_ROOT/$f" || warn "删除本批静态文件失败：$WWW_ROOT/$f"
            else
                warn "静态文件哈希与本批不一致，保留不动：$WWW_ROOT/$f"
            fi
        done
        rmdir -- "$WWW_ROOT" 2>/dev/null && info "已移除本批静态目录：$WWW_ROOT" \
            || warn "静态目录非空，保留：$WWW_ROOT"
    fi
    if [ "$CREATED_SITE_ROOT" -eq 1 ] && [ -d "$SITE_ROOT" ] && [ ! -L "$SITE_ROOT" ]; then
        rmdir -- "$SITE_ROOT" 2>/dev/null && info "已移除本批站点根目录：$SITE_ROOT" \
            || warn "站点根目录非空或不存在，保留：$SITE_ROOT"
    fi

    # 5. 仅在本批 nginx 已确认停止且未自启时恢复标准 default 链接。
    #    这样即使之后重启，也不会因“恢复 default + 仍 enabled”而公开默认站。
    if [ "$REMOVED_DEFAULT_LINK" -eq 1 ] && [ "$service_safe" -eq 1 ] \
        && [ ! -e "$DEFAULT_ENABLED" ] && [ ! -L "$DEFAULT_ENABLED" ] \
        && [ -f "$DEFAULT_AVAILABLE" ] && [ ! -L "$DEFAULT_AVAILABLE" ]; then
        if ln -s -- ../sites-available/default "$DEFAULT_ENABLED"; then
            info "已恢复标准 default 启用链接：$DEFAULT_ENABLED"
        else
            warn "恢复标准 default 链接失败，请人工检查：$DEFAULT_ENABLED"
        fi
    elif [ "$REMOVED_DEFAULT_LINK" -eq 1 ] && [ "$service_safe" -eq 0 ]; then
        warn "未恢复标准 default 链接：无法确认本批 nginx 已停止并取消自启。"
    fi

    # 6. 仅在服务已确认安全时撤掉本批启动抑制；否则按磁盘上真实的抑制状态
    #    保留或重建。释放可能已删除文件却在 rmdir/daemon-reload 阶段失败，
    #    因此不依赖“全部成功才置位”的标记。
    if [ "$SUPPRESSION_CREATED" -eq 1 ]; then
        if [ "$service_safe" -eq 1 ]; then
            release_start_suppression \
                || warn "启动抑制清理失败，请人工检查：$SUPPRESS_CONF 与 $SUPPRESS_HOLD"
        elif suppression_in_place; then
            warn "保留启动抑制：$SUPPRESS_CONF（$SUPPRESS_HOLD）；确认服务已停止并禁用后可手动删除。"
        else
            if ensure_start_suppression; then
                warn "检测到启动抑制未完整在位且服务状态不安全，已重建启动抑制：$SUPPRESS_CONF（$SUPPRESS_HOLD）。"
            else
                warn "重建启动抑制失败；请人工确认 nginx 已停止并执行 systemctl disable nginx，并恢复 $SUPPRESS_CONF 与 $SUPPRESS_HOLD。"
            fi
        fi
    fi

    warn "回退结束；本次部署未完成。"
    return "$status"
}

suppression_conf_is_ours() {
    [ -f "$SUPPRESS_CONF" ] && [ ! -L "$SUPPRESS_CONF" ] || return 1
    [ "$(sha256sum -- "$SUPPRESS_CONF" 2>/dev/null | awk '{print $1}')" = "$SUPPRESS_CONF_HASH" ]
}

# 判定本批启动抑制当前是否仍完整在位（目录、本批配置、常规标记文件）。
# 回退时以此真实磁盘状态判断“保留”还是“重建”，不依赖释放函数的成功标记。
suppression_in_place() {
    [ -d "$SUPPRESS_DIR" ] && [ ! -L "$SUPPRESS_DIR" ] || return 1
    suppression_conf_is_ours || return 1
    [ -f "$SUPPRESS_HOLD" ] && [ ! -L "$SUPPRESS_HOLD" ] || return 1
    return 0
}

# 启用链接归属：必须仍指向本批站点配置，且 inode 与创建时一致。
enabled_link_is_ours() {
    [ -L "$NGINX_ENABLED" ] || return 1
    [ "$(readlink -- "$NGINX_ENABLED" 2>/dev/null)" = "../sites-available/shici-website" ] || return 1
    [ -n "$CREATED_LINK_ID" ] || return 1
    [ "$(stat -c '%d:%i' -- "$NGINX_ENABLED" 2>/dev/null || true)" = "$CREATED_LINK_ID" ]
}

# 建立/补全本批的 nginx 启动抑制。只接受空目录、本批已写入的文件；
# 目录中出现未知条目时拒绝改动（保留并明确失败）。
ensure_start_suppression() {
    local entry name dir_exists=0 write_conf=0 write_hold=0

    if [ ! -d /run/systemd/system ] || ! command -v systemctl >/dev/null 2>&1; then
        warn "systemd 当前不可用，无法建立/确认 nginx 启动抑制。"
        return 1
    fi

    if [ -e "$SUPPRESS_DIR" ] || [ -L "$SUPPRESS_DIR" ]; then
        if [ ! -d "$SUPPRESS_DIR" ] || [ -L "$SUPPRESS_DIR" ]; then
            warn "启动抑制目录路径异常，拒绝改动：$SUPPRESS_DIR"
            return 1
        fi
        dir_exists=1
        while IFS= read -r entry; do
            name="${entry##*/}"
            case "$name" in
                "$SUPPRESS_CONF_NAME"|"$SUPPRESS_HOLD_NAME") ;;
                *)
                    warn "启动抑制目录含未知条目，保留并拒绝改动：$entry"
                    return 1
                    ;;
            esac
        done < <(find "$SUPPRESS_DIR" -mindepth 1 -maxdepth 1 -print 2>/dev/null)
    fi

    if [ -e "$SUPPRESS_CONF" ] || [ -L "$SUPPRESS_CONF" ]; then
        suppression_conf_is_ours || {
            warn "启动抑制配置内容未知，拒绝覆盖：$SUPPRESS_CONF"
            return 1
        }
    else
        write_conf=1
    fi
    if [ -e "$SUPPRESS_HOLD" ] || [ -L "$SUPPRESS_HOLD" ]; then
        [ -f "$SUPPRESS_HOLD" ] && [ ! -L "$SUPPRESS_HOLD" ] || {
            warn "启动抑制标记不是常规文件，拒绝覆盖：$SUPPRESS_HOLD"
            return 1
        }
    else
        write_hold=1
    fi

    if [ "$dir_exists" -eq 0 ]; then
        install -d -o root -g root -m 0755 -- "$SUPPRESS_DIR" || {
            warn "创建启动抑制目录失败：$SUPPRESS_DIR"
            return 1
        }
    fi
    SUPPRESSION_CREATED=1

    if [ "$write_conf" -eq 1 ]; then
        printf '%s\n' "${SUPPRESS_CONF_LINES[@]}" > "$SUPPRESS_CONF" || {
            rm -f -- "$SUPPRESS_CONF"
            warn "写入启动抑制配置失败：$SUPPRESS_CONF"
            return 1
        }
        chmod 0644 -- "$SUPPRESS_CONF" || {
            rm -f -- "$SUPPRESS_CONF"
            warn "设置启动抑制配置权限失败：$SUPPRESS_CONF"
            return 1
        }
    fi
    if [ "$write_hold" -eq 1 ]; then
        : > "$SUPPRESS_HOLD" || {
            rm -f -- "$SUPPRESS_HOLD"
            warn "写入启动抑制标记失败：$SUPPRESS_HOLD"
            return 1
        }
        chmod 0644 -- "$SUPPRESS_HOLD" || {
            rm -f -- "$SUPPRESS_HOLD"
            warn "设置启动抑制标记权限失败：$SUPPRESS_HOLD"
            return 1
        }
    fi

    systemctl daemon-reload >/dev/null 2>&1 || {
        warn "systemctl daemon-reload 失败，无法确认启动抑制已生效。"
        return 1
    }
    return 0
}

# 解除本批启动抑制；只删除内容/归属可确认的本批文件，目录仅空时删除。
release_start_suppression() {
    local dir_ok=1

    if [ "$SUPPRESSION_CREATED" -eq 0 ]; then
        return 0
    fi
    if [ -e "$SUPPRESS_CONF" ] || [ -L "$SUPPRESS_CONF" ]; then
        suppression_conf_is_ours || {
            warn "启动抑制配置内容未知，拒绝删除：$SUPPRESS_CONF"
            return 1
        }
    fi
    if [ -e "$SUPPRESS_HOLD" ] || [ -L "$SUPPRESS_HOLD" ]; then
        [ -f "$SUPPRESS_HOLD" ] && [ ! -L "$SUPPRESS_HOLD" ] || {
            warn "启动抑制标记不是常规文件，拒绝删除：$SUPPRESS_HOLD"
            return 1
        }
    fi

    rm -f -- "$SUPPRESS_CONF" "$SUPPRESS_HOLD" || {
        warn "删除启动抑制文件失败：$SUPPRESS_DIR"
        return 1
    }
    if [ -d "$SUPPRESS_DIR" ]; then
        rmdir -- "$SUPPRESS_DIR" 2>/dev/null || {
            warn "启动抑制目录非空，保留：$SUPPRESS_DIR"
            dir_ok=0
        }
    fi
    systemctl daemon-reload >/dev/null 2>&1 || {
        warn "systemctl daemon-reload 失败，拒绝继续启动 nginx。"
        return 1
    }
    [ "$dir_ok" -eq 1 ] || return 1

    info "已解除仅针对 nginx.service 的启动抑制（此前 nginx -t 已通过）。"
    return 0
}

# 判断本批 apt 阶段是否实际引入了 nginx（含部分安装），不依赖成功标记。
batch_nginx_introduced() {
    local pkg st

    [ "$INSTALL_ATTEMPTED" -eq 1 ] || return 1
    if type -P nginx >/dev/null 2>&1; then
        return 0
    fi
    if [ -e /lib/systemd/system/nginx.service ] || [ -L /lib/systemd/system/nginx.service ] \
        || [ -e /usr/lib/systemd/system/nginx.service ] || [ -L /usr/lib/systemd/system/nginx.service ]; then
        return 0
    fi
    for pkg in nginx nginx-core nginx-full nginx-light nginx-extras nginx-common; do
        st="$(dpkg-query -W -f='${db:Status-Status}' "$pkg" 2>/dev/null | sed -n '1p')"
        case "$st" in
            ''|not-installed|config-files) ;;
            *) return 0 ;;
        esac
    done
    if [ -e /etc/nginx ] || [ -L /etc/nginx ]; then
        return 0
    fi
    return 1
}

# 以下两个判定只接受“明确”的安全观测：命令失败、空输出、未知状态
# 一律视为无法确认并返回 1，不得当作“已停止/未启用”。
nginx_is_confirmed_inactive() {
    local out rc=0
    out="$(systemctl is-active nginx 2>/dev/null)" || rc=$?
    out="$(printf '%s\n' "$out" | sed -n '1p')"
    case "$rc" in
        0|3) ;;
        *) return 1 ;;
    esac
    case "$out" in
        inactive|failed) return 0 ;;
        *) return 1 ;;
    esac
}

nginx_is_confirmed_not_enabled() {
    local out rc=0
    out="$(systemctl is-enabled nginx 2>/dev/null)" || rc=$?
    out="$(printf '%s\n' "$out" | sed -n '1p')"
    case "$rc" in
        0|1) ;;
        *) return 1 ;;
    esac
    case "$out" in
        disabled|masked|masked-runtime) return 0 ;;
        *) return 1 ;;
    esac
}

# 停止并取消本批新引入 nginx 的自启。只处理本次安装带来的单元/链接，
# 不删除未知文件、不卸载软件包、不停止其他服务。
stop_and_disable_batch_nginx() {
    local f resolved mainpid disable_ok=1

    if ! batch_nginx_introduced; then
        return 0
    fi

    if [ ! -d /run/systemd/system ] || ! command -v systemctl >/dev/null 2>&1; then
        warn "安装阶段已开始且发现 nginx 痕迹，但 systemd 当前不可用，无法确认服务状态。"
        return 1
    fi

    if nginx_is_confirmed_inactive; then
        info "本批新装 nginx 当前未运行。"
    else
        # 可能是仍在运行，也可能是状态查询失败/未知；先尝试停止，再用明确观测复查。
        systemctl stop nginx >/dev/null 2>&1 || true
        if ! nginx_is_confirmed_inactive; then
            # 兜底：仅对本批 nginx 主进程发信号，不匹配其他进程名。
            mainpid="$(systemctl show -p MainPID --value nginx 2>/dev/null | sed -n '1p')" || mainpid=""
            case "$mainpid" in
                ''|0|*[!0-9]*) ;;
                *)
                    kill -TERM "$mainpid" 2>/dev/null || true
                    sleep 1
                    if ! nginx_is_confirmed_inactive; then
                        kill -KILL "$mainpid" 2>/dev/null || true
                        sleep 1
                    fi
                    ;;
            esac
        fi
        if ! nginx_is_confirmed_inactive; then
            warn "无法确认本批新装 nginx 已停止（状态查询失败或仍 active）；请立即人工检查（未卸载软件包）。"
            return 1
        fi
        info "已停止本批新装 nginx（软件包保留，未卸载）。"
    fi

    if ! systemctl disable nginx >/dev/null 2>&1; then
        disable_ok=0
        warn "systemctl disable nginx 执行失败；稍后以明确的 is-enabled 观测复核。"
    fi

    # 清理可能残留的启用链接，只删除指向 nginx 软件包单元的链接。
    while IFS= read -r f; do
        [ -L "$f" ] || continue
        resolved="$(readlink -f -- "$f" 2>/dev/null || true)"
        case "$resolved" in
            /lib/systemd/system/nginx.service|/usr/lib/systemd/system/nginx.service)
                rm -f -- "$f" && info "已移除本批 nginx 自启链接：$f"
                ;;
            *)
                warn "保留目标未知的启用链接：$f -> ${resolved:-未知}"
                ;;
        esac
    done < <(find /etc/systemd/system -mindepth 2 -maxdepth 2 -type l -name 'nginx.service' -print 2>/dev/null)

    systemctl daemon-reload >/dev/null 2>&1 || {
        disable_ok=0
        warn "systemctl daemon-reload 失败；不将本次回退视为已确认安全。"
    }

    if ! nginx_is_confirmed_not_enabled; then
        warn "无法确认本批 nginx 已取消自启（查询失败、状态未知或仍为 enabled）；请人工执行 systemctl disable nginx。"
        return 1
    fi

    if ! nginx_is_confirmed_inactive; then
        warn "复查无法确认本批 nginx 已停止（状态查询失败或仍 active）；请立即人工检查。"
        return 1
    fi

    if [ "$disable_ok" -ne 1 ]; then
        warn "systemctl disable 或 daemon-reload 曾执行失败；不将本次回退视为已确认安全。"
        return 1
    fi

    ok "本批新装 nginx 已停止且未启用自启（软件包保留）。"
    return 0
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

port80_lines() {
    # 从 stdin 的 ss -H -ltn 输出中严格筛出本地端口为 80 的监听行。
    # 只看第 4 列 Local Address:Port；IPv6 的 [::]:80 也取最后一段判断。
    awk '
        NF >= 4 {
            n = split($4, parts, ":")
            if (n > 0 && parts[n] == "80") {
                print
            }
        }
    '
}

require_commands() {
    local cmd
    for cmd in apt-get awk basename cmp dpkg-query find grep id install ln readlink rm rmdir sed sha256sum sleep ss stat systemctl wc; do
        type -P "$cmd" >/dev/null 2>&1 || die "缺少必要命令：$cmd"
    done
}

check_os() {
    local os_id
    [ -r /etc/os-release ] || die "无法读取 /etc/os-release，拒绝继续。"
    os_id="$(. /etc/os-release && printf '%s' "${ID:-}")"
    case "$os_id" in
        ubuntu|debian) ok "系统检查通过：$os_id" ;;
        *) die "仅支持 Debian/Ubuntu，当前 ID=${os_id:-unknown}。" ;;
    esac
}

check_bundle_and_sums() {
    local f line name sum_count
    local -A seen=()
    local re='^([0-9a-f]{64}) [ *]([A-Za-z0-9._-]+)$'

    [ -d "$BUNDLE_DIR" ] || die "无法确定当前 bundle 目录：$BUNDLE_DIR"
    info "bundle 目录：$BUNDLE_DIR"

    for f in "${BUNDLE_FILES[@]}"; do
        [ -f "$BUNDLE_DIR/$f" ] || die "bundle 缺少常规文件：$f"
        [ ! -L "$BUNDLE_DIR/$f" ] || die "bundle 文件不能是符号链接：$f"
        [ -s "$BUNDLE_DIR/$f" ] || die "bundle 文件为空：$f"
    done
    [ -f "$BUNDLE_DIR/$SUMS_FILE" ] || die "bundle 缺少常规文件：$SUMS_FILE"
    [ ! -L "$BUNDLE_DIR/$SUMS_FILE" ] || die "bundle 文件不能是符号链接：$SUMS_FILE"
    [ -s "$BUNDLE_DIR/$SUMS_FILE" ] || die "bundle 文件为空：$SUMS_FILE"

    sum_count=0
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || die "SHA256SUMS 含空行，拒绝继续。"
        [[ "$line" =~ $re ]] || die "SHA256SUMS 行格式不合法：$line"
        name="${BASH_REMATCH[2]}"
        case "$name" in
            index.html|styles.css|content.js|app.js|nginx.conf) ;;
            *) die "SHA256SUMS 含未允许的文件名：$name" ;;
        esac
        [ -z "${seen[$name]:-}" ] || die "SHA256SUMS 含重复条目：$name"
        seen[$name]=1
        EXPECTED_HASH[$name]="${BASH_REMATCH[1]}"
        sum_count=$((sum_count + 1))
    done < "$BUNDLE_DIR/$SUMS_FILE"

    [ "$sum_count" -eq 5 ] || die "SHA256SUMS 应恰好包含 5 个条目，实际为 $sum_count。"
    for f in "${BUNDLE_FILES[@]}"; do
        [ -n "${seen[$f]:-}" ] || die "SHA256SUMS 缺少条目：$f"
    done

    info "校验 SHA256SUMS（严格模式）"
    ( cd "$BUNDLE_DIR" && sha256sum --strict -c -- "$SUMS_FILE" ) || die "SHA256SUMS 校验失败。"
    ok "bundle 文件与 SHA256SUMS 全部匹配。"
}

check_no_existing_web_stack() {
    local pkg cmd
    for pkg in nginx nginx-core nginx-full nginx-light nginx-extras nginx-common apache2 apache2-bin caddy; do
        if dpkg-query -W -f='${db:Status-Status}' "$pkg" 2>/dev/null | sed -n '1p' | grep -qx 'installed'; then
            die "检测到已安装软件包：$pkg。本脚本只用于首次部署，拒绝覆盖。"
        fi
    done
    for cmd in nginx apache2 apachectl caddy httpd; do
        if type -P "$cmd" >/dev/null 2>&1; then
            die "检测到已有可执行命令：$cmd。本脚本只用于首次部署，拒绝覆盖。"
        fi
    done

    if [ -e /etc/nginx ] || [ -L /etc/nginx ]; then
        die "已存在 /etc/nginx，可能含未知配置；拒绝继续。"
    fi
    if [ -e "$SITE_ROOT" ] || [ -L "$SITE_ROOT" ]; then
        die "目标路径已存在，拒绝覆盖：$SITE_ROOT"
    fi
    if [ -e "$NGINX_AVAILABLE" ] || [ -L "$NGINX_AVAILABLE" ]; then
        die "目标路径已存在，拒绝覆盖：$NGINX_AVAILABLE"
    fi
    if [ -e "$NGINX_ENABLED" ] || [ -L "$NGINX_ENABLED" ]; then
        die "目标路径已存在，拒绝覆盖：$NGINX_ENABLED"
    fi
    if [ ! -d /srv ] || [ -L /srv ]; then
        die "/srv 不是常规目录，拒绝继续。"
    fi

    # 不覆盖任何已有 systemd 单元/抑制对象；本批只允许在完全空白时创建 nginx.service.d。
    for path in \
        /etc/systemd/system/nginx.service \
        /etc/systemd/system/nginx.service.d \
        /run/systemd/system/nginx.service \
        /usr/lib/systemd/system/nginx.service \
        /lib/systemd/system/nginx.service \
        /etc/init.d/nginx; do
        if [ -e "$path" ] || [ -L "$path" ]; then
            die "检测到已有 systemd/init 对象：$path。本脚本只用于首次部署，拒绝覆盖。"
        fi
    done
    if command -v systemctl >/dev/null 2>&1 && systemctl cat nginx.service >/dev/null 2>&1; then
        die "systemd 已存在 nginx.service 单元。本脚本只用于首次部署，拒绝覆盖。"
    fi

    ok "未发现已有 nginx/apache/caddy、/etc/nginx、nginx systemd 对象或同名部署路径。"
}

# 在任何可能启动 nginx 的装包步骤前建立启动抑制。
# 若无法确认 systemd 可用或存在未知对象，宁可拒绝安装，也不制造中途默认页窗口。
prepare_start_suppression() {
    local state

    command -v systemctl >/dev/null 2>&1 || die "缺少 systemctl，无法建立启动抑制，拒绝安装 nginx。"
    [ -d /run/systemd/system ] || die "systemd 未运行（/run/systemd/system 不存在），拒绝安装 nginx。"

    state="$(systemctl is-system-running 2>/dev/null || true)"
    case "$state" in
        running|degraded|starting|maintenance|initializing) ;;
        '') die "无法确认 systemd 运行状态，拒绝安装 nginx。" ;;
        *) die "systemd 状态为 $state，无法建立启动抑制，拒绝安装 nginx。" ;;
    esac

    if systemctl cat nginx.service >/dev/null 2>&1; then
        die "systemd 已存在 nginx.service 单元，拒绝建立抑制/安装。"
    fi
    if [ -e "$SUPPRESS_DIR" ] || [ -L "$SUPPRESS_DIR" ]; then
        die "已存在 $SUPPRESS_DIR，可能属于其他部署；拒绝覆盖。"
    fi
    if [ -e "$SUPPRESS_CONF" ] || [ -L "$SUPPRESS_CONF" ] \
        || [ -e "$SUPPRESS_HOLD" ] || [ -L "$SUPPRESS_HOLD" ]; then
        die "已存在启动抑制同名文件；拒绝覆盖。"
    fi

    ensure_start_suppression \
        || die "建立 nginx 启动抑制失败，拒绝安装 nginx（避免自动公开默认站）。"
    ok "已建立仅针对 nginx.service 的启动抑制：$SUPPRESS_CONF（标记 $SUPPRESS_HOLD）。"
}

check_port_80_free() {
    local ss_out lines
    ss_out="$(ss -H -ltn)" || die "ss 执行失败，无法确认 TCP 80 状态。"
    lines="$(printf '%s\n' "$ss_out" | port80_lines)"
    if [ -n "$lines" ]; then
        printf '%s\n' "$lines" >&2
        die "TCP 80 已有监听，拒绝继续（不停止该服务）。"
    fi
    ok "TCP 80（IPv4/IPv6）当前无监听。"
}

install_nginx_package() {
    local ver pkg pkg_ok
    info "执行 apt-get update"
    apt-get update || die "apt-get update 失败。"

    # apt 更新可能耗时；安装前再次确认没有并发出现的 nginx/配置。
    if [ -e /etc/nginx ] || [ -L /etc/nginx ]; then
        die "安装前检测到 /etc/nginx 已出现，拒绝继续。"
    fi
    if type -P nginx >/dev/null 2>&1; then
        die "安装前检测到 nginx 命令已出现，拒绝继续。"
    fi
    suppression_conf_is_ours && [ -f "$SUPPRESS_HOLD" ] && [ ! -L "$SUPPRESS_HOLD" ] \
        || die "安装前启动抑制缺失或被改动，拒绝安装（避免自动公开默认站）。"

    INSTALL_ATTEMPTED=1
    # NEEDRESTART_MODE=l 仅作用于本次 apt-get 进程，避免 needrestart 自动重启既有服务；
    # 不写全局策略。两个包固定为本批已审版本，apt 解析不到该版本会直接失败。
    info "安装 nginx 与 nginx-common ${NGINX_PKG_VERSION}（--no-install-recommends；不购买任何服务）"
    NEEDRESTART_MODE=l apt-get install -y --no-install-recommends \
        "nginx=${NGINX_PKG_VERSION}" "nginx-common=${NGINX_PKG_VERSION}" \
        || die "nginx 安装失败：固定版本 ${NGINX_PKG_VERSION} 不可用或未完成；不降级、不替换。已安装的包可能保留，本脚本不卸载。"

    type -P nginx >/dev/null 2>&1 || die "安装后仍未找到 nginx 命令。"
    for pkg in nginx nginx-common; do
        [ "$(dpkg-query -W -f='${Version}' "$pkg" 2>/dev/null | sed -n '1p')" = "$NGINX_PKG_VERSION" ] \
            || die "安装后 $pkg 版本不是本批已审版本 ${NGINX_PKG_VERSION}。"
    done
    pkg_ok=0
    for pkg in nginx nginx-core nginx-full nginx-light nginx-extras; do
        if dpkg-query -W -f='${db:Status-Status}' "$pkg" 2>/dev/null | sed -n '1p' | grep -qx 'installed'; then
            pkg_ok=1
            break
        fi
    done
    [ "$pkg_ok" -eq 1 ] || die "未检测到处于 installed 状态的 nginx 软件包。"
    [ -f /etc/nginx/nginx.conf ] && [ ! -L /etc/nginx/nginx.conf ] \
        || die "安装后未找到常规的 /etc/nginx/nginx.conf。"
    [ -d /etc/nginx/sites-available ] || die "安装后缺少 /etc/nginx/sites-available。"
    [ -d /etc/nginx/sites-enabled ] || die "安装后缺少 /etc/nginx/sites-enabled。"

    ver="$(nginx -v 2>&1 || true)"
    ok "nginx 安装完成：${ver:-版本未知}"
}

check_post_install_layout() {
    local entry nginx_path nginx_bin owner_pkg
    local -a enabled_entries=()
    local -a conf_entries=()

    [ -d /etc/nginx ] && [ ! -L /etc/nginx ] \
        || die "安装后 /etc/nginx 不是常规目录。"
    [ -d /etc/nginx/sites-available ] && [ ! -L /etc/nginx/sites-available ] \
        || die "安装后 sites-available 不是常规目录。"
    [ -d /etc/nginx/sites-enabled ] && [ ! -L /etc/nginx/sites-enabled ] \
        || die "安装后 sites-enabled 不是常规目录。"
    if [ -e /etc/nginx/conf.d ] || [ -L /etc/nginx/conf.d ]; then
        [ -d /etc/nginx/conf.d ] && [ ! -L /etc/nginx/conf.d ] \
            || die "安装后 conf.d 不是常规目录。"
    fi

    # 装包后启动抑制必须仍在，且 nginx 不得已被自动启动。
    suppression_conf_is_ours \
        || die "安装后启动抑制配置缺失或被改动，拒绝继续（避免公开默认站）。"
    [ -f "$SUPPRESS_HOLD" ] && [ ! -L "$SUPPRESS_HOLD" ] \
        || die "安装后启动抑制标记缺失或被改动，拒绝继续。"
    if systemctl is-active --quiet nginx 2>/dev/null; then
        die "安装后 nginx 已处于运行状态，启动抑制未生效；中止部署（回退将停止本批新装服务）。"
    fi

    shopt -s nullglob
    for entry in /etc/nginx/sites-enabled/*; do
        enabled_entries+=("$entry")
    done
    conf_entries=(/etc/nginx/conf.d/*.conf)
    shopt -u nullglob

    for entry in "${enabled_entries[@]}"; do
        if [ "$(basename -- "$entry")" != "default" ]; then
            die "发现其他已启用站点：$entry。中止；未知站点文件保留不删，本批新装 nginx 将由回退停止并取消自启。"
        fi
    done

    for entry in "${conf_entries[@]}"; do
        die "发现未预期的 /etc/nginx/conf.d 配置：$entry。中止；未知配置保留不删，本批新装 nginx 将由回退停止并取消自启。"
    done

    nginx_path="$(type -P nginx || true)"
    [ -n "$nginx_path" ] || die "安装后无法定位 nginx 可执行文件。"
    nginx_bin="$(readlink -f -- "$nginx_path" || true)"
    [ -n "$nginx_bin" ] || die "无法解析 nginx 可执行文件路径。"
    owner_pkg="$(dpkg-query -S "$nginx_bin" 2>/dev/null | sed -n '1s/:.*//p' || true)"
    case "$owner_pkg" in
        nginx*) ;;
        *) die "nginx 可执行文件不属于 nginx 软件包（dpkg 归属：${owner_pkg:-未知}）。" ;;
    esac

    ok "安装后的站点/配置布局符合首次部署预期。"
}

remove_stock_default_site() {
    local resolved owner_pkg
    if [ ! -e "$DEFAULT_ENABLED" ] && [ ! -L "$DEFAULT_ENABLED" ]; then
        info "未发现 sites-enabled/default，无需移除默认站链接。"
        return 0
    fi
    [ -L "$DEFAULT_ENABLED" ] || die "sites-enabled/default 不是符号链接，拒绝改动。"
    [ -f "$DEFAULT_AVAILABLE" ] && [ ! -L "$DEFAULT_AVAILABLE" ] \
        || die "标准 default 配置缺失或不是常规文件，拒绝改动。"

    resolved="$(readlink -f -- "$DEFAULT_ENABLED" 2>/dev/null || true)"
    [ "$resolved" = "$DEFAULT_AVAILABLE" ] \
        || die "sites-enabled/default 未指向标准配置，拒绝改动。"

    owner_pkg="$(dpkg-query -S "$DEFAULT_AVAILABLE" 2>/dev/null | sed -n '1s/:.*//p' || true)"
    case "$owner_pkg" in
        nginx*) ;;
        *) die "标准 default 配置不属于 nginx 软件包，拒绝改动（dpkg 归属：${owner_pkg:-未知}）。" ;;
    esac

    REMOVED_DEFAULT_LINK=1
    rm -f -- "$DEFAULT_ENABLED" || die "移除标准 default 链接失败，请人工检查。"
    ok "已移除本次新安装的标准 default 站点链接（失败时可恢复）。"
}

check_port_80_owner_is_new_nginx() {
    local ss_out lines pids pid exe nginx_path nginx_bin
    ss_out="$(ss -H -ltnp)" || die "ss -p 执行失败，无法确认 80 端口归属。"
    lines="$(printf '%s\n' "$ss_out" | port80_lines)"

    if [ -z "$lines" ]; then
        return 0
    fi

    pids="$(printf '%s\n' "$lines" | awk '
        {
            if (index($0, "users:((\"nginx\"") == 0) {
                bad = 1
            }
            rest = $0
            while (match(rest, /pid=[0-9]+/)) {
                print substr(rest, RSTART + 4, RLENGTH - 4)
                rest = substr(rest, RSTART + RLENGTH)
            }
        }
        END { if (bad) exit 2 }
    ')" || {
        printf '%s\n' "$lines" >&2
        die "安装后 TCP 80 被非 nginx 进程占用；中止，回退不会停止该服务。"
    }

    [ -n "$pids" ] || {
        printf '%s\n' "$lines" >&2
        die "无法确认 TCP 80 监听进程，拒绝继续。"
    }

    nginx_path="$(type -P nginx || true)"
    nginx_bin="$(readlink -f -- "$nginx_path" 2>/dev/null || true)"
    if [ -z "$nginx_path" ] || [ -z "$nginx_bin" ]; then
        die "无法解析本批 nginx 可执行文件路径。"
    fi
    for pid in $pids; do
        exe="$(readlink -f -- "/proc/$pid/exe" 2>/dev/null || true)"
        if [ "$exe" != "$nginx_bin" ]; then
            die "TCP 80 由非本批 nginx 占用（pid=$pid, exe=${exe:-未知}）；中止，回退不会停止该服务。"
        fi
    done
    ok "当前 TCP 80 监听进程均为本次新装 nginx。"
}

create_site_files() {
    local f count
    [ ! -e "$SITE_ROOT" ] && [ ! -L "$SITE_ROOT" ] || die "目标路径已存在，拒绝覆盖：$SITE_ROOT"

    install -d -o root -g root -m 0755 -- "$SITE_ROOT"
    CREATED_SITE_ROOT=1
    install -d -o root -g root -m 0755 -- "$WWW_ROOT"
    CREATED_WWW=1

    [ "$(stat -c '%U:%G %a' -- "$SITE_ROOT")" = "root:root 755" ] \
        || die "站点根目录属主/权限不符合预期：$SITE_ROOT"
    [ "$(stat -c '%U:%G %a' -- "$WWW_ROOT")" = "root:root 755" ] \
        || die "静态目录属主/权限不符合预期：$WWW_ROOT"

    for f in "${STATIC_FILES[@]}"; do
        install -o root -g root -m 0644 -- "$BUNDLE_DIR/$f" "$WWW_ROOT/$f"
        cmp -s -- "$BUNDLE_DIR/$f" "$WWW_ROOT/$f" || die "静态文件复制校验失败：$f"
        [ -n "${EXPECTED_HASH[$f]:-}" ] \
            && [ "$(sha256sum -- "$WWW_ROOT/$f" | awk '{print $1}')" = "${EXPECTED_HASH[$f]}" ] \
            || die "静态文件哈希与 SHA256SUMS 不符：$f"
        [ "$(stat -c '%U:%G %a' -- "$WWW_ROOT/$f")" = "root:root 644" ] \
            || die "静态文件属主/权限不符合预期：$WWW_ROOT/$f"
    done

    count="$(find "$WWW_ROOT" -mindepth 1 -maxdepth 1 | wc -l)"
    [ "$count" -eq 4 ] || die "静态目录文件数量异常（应为 4，实际 $count）。"
    ok "已创建 $WWW_ROOT，仅含四个静态文件（root:root，目录 755 / 文件 644）。"
}

install_site_config() {
    [ ! -e "$NGINX_AVAILABLE" ] && [ ! -L "$NGINX_AVAILABLE" ] \
        || die "目标路径已存在，拒绝覆盖：$NGINX_AVAILABLE"
    [ ! -e "$NGINX_ENABLED" ] && [ ! -L "$NGINX_ENABLED" ] \
        || die "目标路径已存在，拒绝覆盖：$NGINX_ENABLED"

    install -o root -g root -m 0644 -- "$BUNDLE_DIR/nginx.conf" "$NGINX_AVAILABLE"
    INSTALLED_CONF=1
    cmp -s -- "$BUNDLE_DIR/nginx.conf" "$NGINX_AVAILABLE" \
        || die "站点配置复制校验失败：$NGINX_AVAILABLE"
    [ -n "${EXPECTED_HASH[nginx.conf]:-}" ] \
        && [ "$(sha256sum -- "$NGINX_AVAILABLE" | awk '{print $1}')" = "${EXPECTED_HASH[nginx.conf]}" ] \
        || die "站点配置哈希与 SHA256SUMS 不符：$NGINX_AVAILABLE"
    [ "$(stat -c '%U:%G %a' -- "$NGINX_AVAILABLE")" = "root:root 644" ] \
        || die "站点配置属主/权限不符合预期：$NGINX_AVAILABLE"

    ln -s -- ../sites-available/shici-website "$NGINX_ENABLED"
    [ -L "$NGINX_ENABLED" ] \
        || die "启用链接创建失败：$NGINX_ENABLED"
    [ "$(readlink -- "$NGINX_ENABLED")" = "../sites-available/shici-website" ] \
        || die "启用链接目标不符合预期：$NGINX_ENABLED"
    # 记录创建时的链接身份（设备号:inode），回退时据此确认仍是本批对象。
    CREATED_LINK_ID="$(stat -c '%d:%i' -- "$NGINX_ENABLED" 2>/dev/null || true)"
    [ -n "$CREATED_LINK_ID" ] || die "无法确认启用链接身份：$NGINX_ENABLED"
    CREATED_LINK=1
    [ "$(find /etc/nginx/sites-enabled -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ] \
        || die "sites-enabled 中除本批链接外还有其他条目，拒绝继续。"
    ok "已安装站点配置与唯一启用链接。"
}

activate_site() {
    info "执行 nginx -t（此时仍处于启动抑制，服务未公开）"
    nginx -t || die "nginx -t 未通过，配置未启用。"
    ok "nginx -t 通过。"

    if systemctl is-active --quiet nginx 2>/dev/null; then
        die "启动抑制期间 nginx 意外处于运行状态，拒绝继续（回退将停止该新装服务）。"
    fi

    # 配置已校验、站点目录与启用链接已就绪，才解除抑制；这是首次受控公开。
    release_start_suppression || die "解除启动抑制失败，拒绝启动 nginx。"

    if systemctl is-active --quiet nginx 2>/dev/null; then
        systemctl reload nginx || die "systemctl reload nginx 失败。"
        ok "已 graceful reload nginx。"
    else
        systemctl start nginx || die "systemctl start nginx 失败。"
        ok "nginx 原先未运行，已 start（首次公开）。"
    fi

    systemctl is-active --quiet nginx || die "nginx 未处于 active 状态。"
    systemctl enable nginx >/dev/null 2>&1 || die "systemctl enable nginx 失败。"
    systemctl is-enabled --quiet nginx || die "nginx 未处于 enabled 状态。"
    ok "nginx 已 active，并已 enable 开机自启。"
}

final_verify() {
    local ss_out lines
    ss_out="$(ss -H -ltnp)" || die "最终检查时 ss -p 执行失败。"
    lines="$(printf '%s\n' "$ss_out" | port80_lines)"
    [ -n "$lines" ] || die "最终检查未发现 TCP 80 监听。"
    printf '%s\n' "$lines" | awk '
        {
            if (index($0, "users:((\"nginx\"") == 0) {
                bad = 1
            }
        }
        END { if (bad) exit 2 }
    ' || die "最终检查发现 TCP 80 并非全部由 nginx 监听。"
    ok "最终本机检查：TCP 80 已由 nginx 监听（未做外网 HTTP 测试）。"
}

main() {
    [ "$#" -eq 0 ] || die "本脚本不接受参数；请在已上传 bundle 目录内执行 bash install.sh"
    [ "$(id -u)" -eq 0 ] || die "必须以 root 运行（当前 uid=$(id -u)）。"

    info "==== 阶段 1/7：前置检查 ===="
    require_commands
    check_os
    check_bundle_and_sums
    check_no_existing_web_stack
    check_port_80_free

    info "==== 阶段 2/7：建立 nginx 启动抑制（装包前） ===="
    prepare_start_suppression

    info "==== 阶段 3/7：安装 nginx ===="
    install_nginx_package
    check_post_install_layout
    check_port_80_owner_is_new_nginx

    info "==== 阶段 4/7：处理标准默认站 ===="
    remove_stock_default_site

    info "==== 阶段 5/7：创建站点目录与静态文件 ===="
    create_site_files

    info "==== 阶段 6/7：安装站点配置 ===="
    install_site_config

    info "==== 阶段 7/7：校验、解除抑制并启用 ===="
    activate_site
    final_verify

    SUCCESS=1
    printf '\n'
    ok "本次部署脚本执行完成（仅代表本机阶段成功，不代表外网已可访问或已验收）。"
    printf '%s\n' \
        "阶段状态：" \
        "  - 前置检查：通过" \
        "  - 装包前启动抑制：已建立，并在 nginx -t 通过后解除" \
        "  - nginx 软件包：本次由 apt 新装" \
        "  - 静态文件：四个，位于 $WWW_ROOT（root:root，目录 755 / 文件 644）" \
        "  - 站点配置：$NGINX_AVAILABLE" \
        "  - 启用链接：$NGINX_ENABLED" \
        "  - nginx -t：通过" \
        "  - 服务：active 且 enabled" \
        "  - TCP 80：本机已由 nginx 监听" \
        "未做：外网 HTTP 访问测试、域名/DNS、HTTPS/443、防火墙/云安全组、APK/仓库/模型/词库/学习数据发布。"
}

main "$@"
