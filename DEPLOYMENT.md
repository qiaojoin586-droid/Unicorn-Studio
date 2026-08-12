# 阿里云部署说明

推荐结构：

浏览器 -> 阿里云安全组 -> Nginx (HTTPS) -> Node (127.0.0.1:3000)

Node 不直接暴露公网。Nginx 负责 TLS、连接数限制和入口限速，Node 继续负责登录校验、会话、API 和 Vue history fallback。

## 1. 准备生产配置

在项目根目录创建 .env，至少修改密码和随机密钥：

~~~env
NODE_ENV=production
HOST=127.0.0.1
PORT=3000
LEARNING_PASSWORD=至少12位且不可复用的访问密码
SESSION_SECRET=至少32位的随机字符串
COOKIE_SECURE=true
SESSION_TTL_MS=28800000
LOGIN_RATE_LIMIT_MAX=10
LOGIN_RATE_LIMIT_WINDOW_MS=600000
LOGIN_RATE_LIMIT_MAX_IPS=10000
TRUST_PROXY=true
~~~

生成随机会话密钥：

~~~bash
node -e "console.log(require('node:crypto').randomBytes(48).toString('base64url'))"
~~~

生产模式会拒绝弱密码、占位 SESSION_SECRET 和缺失的 dist 构建产物，避免带着不安全配置上线。

## 2. 安装和构建

~~~bash
cd /opt/unicorn-studio
npm ci
npm run build
sudo useradd --system --home /opt/unicorn-studio --shell /usr/sbin/nologin unicorn
sudo chown -R root:unicorn /opt/unicorn-studio
sudo chmod 755 /opt/unicorn-studio
sudo chmod 640 /opt/unicorn-studio/.env
sudo find /opt/unicorn-studio/dist -type d -exec chmod 755 {} \;
sudo find /opt/unicorn-studio/dist -type f -exec chmod 644 {} \;
~~~

服务运行期间不需要写项目目录。部署新版本时由管理员更新文件并重新构建。

## 3. 启用 systemd

~~~bash
sudo cp deploy/unicorn-studio.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now unicorn-studio
sudo systemctl status unicorn-studio
curl http://127.0.0.1:3000/api/health
~~~

如果 Node 不在 /usr/bin/node，用 which node 确认路径并修改服务文件的 ExecStart。

## 4. 配置 Nginx 和 HTTPS

将 deploy/nginx.conf.example 放入 Nginx 的 http 配置范围，替换域名与证书路径，然后检查并重载：

~~~bash
sudo nginx -t
sudo systemctl reload nginx
~~~

配置包含：

- HTTP 强制跳转 HTTPS。
- 每个来源 IP 最多 20 个并发连接。
- 登录接口每分钟 6 个请求，允许短时突发 4 个。
- 转发真实来源 IP，供 Node 的第二层登录限流使用。
- 哈希静态资源由 Nginx 直接传输并缓存一年，视频流量不占 Node 处理能力。
- 页面路由回源 Node，因此直接访问 /learning、/works 等地址不会 404。
- 支持视频 Range 分段请求。

## 5. 阿里云安全组

入方向只开放：

- 80/TCP：用于跳转 HTTPS 和证书续期。
- 443/TCP：网站访问。
- 22/TCP：仅允许管理员固定公网 IP，禁止 0.0.0.0/0。

不要开放 3000/TCP。Node 已绑定 127.0.0.1，即使安全组误配也不会直接接受公网连接。

## 6. 上线检查

~~~bash
curl -I https://example.com/
curl -I https://example.com/learning
curl https://example.com/api/health
curl -I -H "Range: bytes=0-1023" https://example.com/assets/实际视频文件名.mp4
journalctl -u unicorn-studio -n 100 --no-pager
~~~

预期 /learning 返回 200，Range 请求返回 206，响应包含安全头。密码接口连续失败后应返回 429。

实际并发上限必须在目标 ECS 规格和带宽下压测。视频建议放到阿里云 OSS + CDN；即使应用服务器能维持很多空闲连接，公网带宽仍会先成为瓶颈。