#!/usr/bin/env bash
#
# 生产启动脚本（B-18）
#
# 为什么需要它：本项目此前没有任何启动脚本、Dockerfile 或 JVM 参数，
# 实际是以 JVM 默认值运行的 —— 即 MaxHeapSize = 物理内存 / 4。
# 那意味着三件事都没有：没有堆上限意识、OOM 时不产生堆转储（排查等于盲猜）、
# OOM 后进程可能带病存活继续对外服务。
#
# 用法：
#   ./start.sh                  # 前台启动
#   nohup ./start.sh > /dev/null 2>&1 &   # 后台启动
#
# 环境变量（生产必配，缺失会拒绝启动，见 SecretsStartupValidator）：
#   ADMIN_JWT_SECRET     后台 token 签名密钥，≥32 字符
#   PUBLIC_JWT_SECRET    前台 token 签名密钥，≥32 字符
#   APP_CRYPTO_SECRET    敏感配置加密密钥，≥32 字符【随机串，非人类可记口令】
#   DB_PASSWORD          MySQL 口令
#   REDIS_HOST / REDIS_PASSWORD
#   TRUSTED_PROXY_COUNT  可信代理层数（本部署为 nginx → 后端 = 1）
#
# 可选环境变量：
#   LOG_DIR              日志目录。本脚本已自动设为 <应用目录>/logs（绝对路径），
#                        要把日志放到别处（如 /var/log/unicorn-studio）时在调用前覆盖它即可。
#   FILE_UPLOAD_DIR      上传文件根目录。本脚本已自动设为 <应用目录>/data/uploads（绝对路径）。
#                        ⚠️ 它同时是 FileService 的【写入目录】与 /uploads/** 的【读取目录】，
#                        必须与磁盘上既有上传文件的实际位置一致；若上传文件不在应用目录下，
#                        请在调用前显式覆盖（详见下方注释）。
#
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_HOME"

# 取 target 下的可执行 jar（排除 .jar.original 与 sources）
JAR="$(find "$APP_HOME/target" -maxdepth 1 -name 'unicorn-studio-*.jar' \
        ! -name '*.original' ! -name '*-sources.jar' 2>/dev/null | head -n 1)"
if [[ -z "$JAR" ]]; then
  echo "未找到可执行 jar，请先执行：./mvnw clean package -DskipTests" >&2
  exit 1
fi

# 日志与堆转储目录（logs/ 已 gitignore）。
#
# ⚠️ 这里【必须】把 LOG_DIR 钉成绝对路径，否则「日志不在 logs/ 里」这一类问题会反复出现：
#   application.yml 的 logging.file.path 默认 ./logs，是相对【启动工作目录】解析的。
#   只要不是在应用目录里敲命令（例如 `java -jar /opt/unicorn-studio/xxx.jar` 而 cwd=/root，
#   或 systemd 的 WorkingDirectory 没设成应用目录），日志就会落到别处；
#   若那个目录还不可写，logback 只会把错误写进自己的内部状态，结果就是
#   「控制台有日志、logs/ 里什么都没有」。
#   LOG_DIR 一旦是绝对路径，日志位置就与「在哪个目录启动」彻底无关。
#
# 同一个变量还决定两件事：heapdump 的落点（下面 -XX:HeapDumpPath）与
# RetentionService 清理 admin 审计文件的目录（它读的就是 ${logging.file.path}）。
#
# 用 `: "${LOG_DIR:=默认}"`：外部（systemd 的 Environment=、手工 export）显式给了值就尊重它，
# 没给才落到应用目录下 —— 不要写成无条件赋值，那会把运维侧的配置悄悄顶掉。
: "${LOG_DIR:=$APP_HOME/logs}"
HEAP_DUMP_DIR="$LOG_DIR"
mkdir -p "$HEAP_DUMP_DIR"

# ⚠️ 上传目录是同一类问题，但**更危险**：app.upload.dir（默认 data/uploads）同样相对启动工作目录，
#    而且它同时决定 /uploads/** 静态映射【读哪个目录】。一旦与当初写入时不一致，就表现为
#    「图片文件还在、数据库记录也没变，前端主站却拿到 {"code":404,"message":"资源不存在"}」。
#    这种情况已经实际发生过两次（见 WebMvcConfig / UploadProperties 的注释），故本脚本**主动钉住**默认值：
#
#    `: "${FILE_UPLOAD_DIR:=$APP_HOME/data/uploads}"` 与上面 LOG_DIR 同一套路 ——
#      · 外部（systemd 的 Environment=、手工 export）显式给了值就尊重它；
#      · 没给才落到 <应用目录>/data/uploads，即「上传文件与日志都跟着应用目录走」。
#    这样「写盘目录」与「读取目录」永远是同一个，且与「在哪个目录敲命令」无关。
#    ⚠️ 若你把上传文件放在别处（例如 /srv/unicorn/data/uploads），必须显式导出该变量 ——
#       否则服务会在 <应用目录>/data/uploads 新建一个空目录，
#       而数据库里已有的 /uploads/... 记录会全部 404。
: "${FILE_UPLOAD_DIR:=$APP_HOME/data/uploads}"
export FILE_UPLOAD_DIR

# APP_HOME 一并向子进程导出：应用侧（UploadProperties）在 FILE_UPLOAD_DIR 为相对路径时
# 以它为基准解析，从而不再依赖启动工作目录。
export APP_HOME

exec java \
  -Xms512m -Xmx1g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$HEAP_DUMP_DIR/heapdump.hprof" \
  -XX:+ExitOnOutOfMemoryError \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai \
  -jar "$JAR" "$@"

# ---------------------------------------------------------------------------
# 各项参数的取舍说明（改动前请先读这段）
#
# -Xms512m -Xmx1g
#   本站规模（单体应用 + Druid 连接池 20）1g 足够。堆不是越大越好：堆越大，
#   G1 单次 Full GC 的停顿越久，而本站对象的生命周期普遍很短。
#   ⚠️ 堆大小的历史约束是 B-17 的图片解压炸弹（10MB 的图能解出数百 MB 堆），
#      该问题已修复（上传改为先读文件头判尺寸、再降采样解码），故 1g 宽裕。
#      若将来放宽图片像素上限，必须同步重估这个值。
#
# -XX:+UseG1GC -XX:MaxGCPauseMillis=200
#   Java 21 的默认收集器已是 G1，这里显式写出是为了「不依赖默认值」——
#   默认值会随 JDK 版本变化，显式声明让行为可预期。200ms 是低延迟取向的停顿目标。
#
# -XX:+HeapDumpOnOutOfMemoryError / -XX:HeapDumpPath
#   OOM 时自动落堆转储。没有它，OOM 排查只能靠猜。
#   ⚠️ 堆转储文件【含有明文 API Key、Redis/DB 口令】等一切运行时明文
#      （见 ActuatorSecurityConfig 的说明），属敏感文件：权限收紧、不要外传、
#      排查完及时删除。
#
# -XX:+ExitOnOutOfMemoryError
#   OOM 后直接退出，交由 systemd/容器重启，而不是让一个已经不可信的进程继续对外服务。
#   注意这与 -XX:+HeapDumpOnOutOfMemoryError 配合使用：先落转储，再退出。
#
# -Dfile.encoding=UTF-8
#   Java 18 起（JEP 400）file.encoding 默认已是 UTF-8，此处在 Java 21 上属冗余的显式声明。
#   保留它是因为本项目为修 Windows 控制台乱码把【控制台】编码设成了 GBK
#   （application.yml 的 logging.charset.console），显式声明可避免将来有人误改系统代码页
#   而影响文件与网络编码。
#
# -Duser.timezone=Asia/Shanghai
#   全链路按「无时区 + 固定 Asia/Shanghai」约定（见 CacheObjectMapper 的口径说明）。
#   显式固定可避免容器/服务器时区不同导致的时间错位。
#
# 未启用：虚拟线程（spring.threads.virtual.enabled）
#   本项目绝大多数请求是「阻塞等 DB / 等 Redis」的 IO 密集型，确实是虚拟线程的适用场景，
#   但开启前必须先确认连接池已扩容（B-06 已扩到 20），且需压测确认无 pinning。
#   保留 spring.threads.virtual.enabled: false 作为一键回滚，压测通过后再单独评估。
# ---------------------------------------------------------------------------
