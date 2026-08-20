#!/bin/bash
set -euo pipefail

APP_DIR=/opt/wx-ai
APP_PORT=18080

echo "==> 检查环境"
if ! command -v java >/dev/null 2>&1; then
  echo "安装 Java 17..."
  yum install -y java-17-openjdk java-17-openjdk-devel || dnf install -y java-17-openjdk java-17-openjdk-devel
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "安装 Maven..."
  yum install -y maven || dnf install -y maven
fi
if ! command -v nginx >/dev/null 2>&1; then
  echo "安装 Nginx..."
  yum install -y nginx || dnf install -y nginx
fi

java -version
mvn -v | head -1

echo "==> 构建项目"
cd "$APP_DIR"
mvn -q -DskipTests package

JAR=$(ls -1 target/*.jar | grep -v original | head -1)
if [ -z "$JAR" ]; then
  echo "未找到 jar 包"
  exit 1
fi

echo "==> 写入 systemd 服务"
cat >/etc/systemd/system/wx-ai.service <<EOF
[Unit]
Description=WeChat AI Service
After=network.target

[Service]
Type=simple
WorkingDirectory=${APP_DIR}
EnvironmentFile=${APP_DIR}/.env
ExecStart=/usr/bin/java -jar ${APP_DIR}/${JAR}
Restart=always
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable wx-ai
systemctl restart wx-ai
sleep 3
systemctl --no-pager --full status wx-ai || true

echo "==> 配置 Nginx"
cat >/etc/nginx/conf.d/wx-ai.conf <<EOF
server {
    listen 80;
    server_name 1.117.207.253;

    location /wx/ {
        proxy_pass http://127.0.0.1:${APP_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_http_version 1.1;
    }
}
EOF

# 应用默认 8080，若被占用则改端口
if ss -lntp | grep -q ':8080 '; then
  echo "8080 已被占用，应用使用 ${APP_PORT}"
  mkdir -p /etc/systemd/system/wx-ai.service.d
  cat >/etc/systemd/system/wx-ai.service.d/override.conf <<EOF
[Service]
Environment=SERVER_PORT=${APP_PORT}
EOF
  systemctl daemon-reload
  systemctl restart wx-ai
else
  APP_PORT=8080
  sed -i "s#http://127.0.0.1:18080#http://127.0.0.1:8080#" /etc/nginx/conf.d/wx-ai.conf
fi

nginx -t
systemctl enable nginx
systemctl restart nginx || systemctl reload nginx

echo "==> 健康检查"
sleep 2
curl -s "http://127.0.0.1:${APP_PORT}/wx/msg?signature=test&timestamp=123&nonce=abc&echostr=hello" || true
echo
echo "部署完成。测试号 URL 填: http://1.117.207.253/wx/msg"
echo "Token 填: group4token2026"

---
Wait, I used SERVER_PORT but Spring Boot uses server.port. I should fix that to use --server.port or SPRING_APPLICATION_JSON or JAVA_TOOL_OPTIONS.

Also application.yml has server.port: 8080. Environment variable would be SERVER_PORT in Spring Boot relaxed binding - actually Spring Boot maps SERVER_PORT to server.port. Yes SERVER_PORT works.

But I hardcoded APP_PORT=18080 in nginx first then maybe change. Let me fix the script to be cleaner.

Also I should not write a broken script. Let me rewrite more carefully.

And I accidentally included my thinking in the file. Need to fix. I started writing the file and then included thinking. The write tool already wrote it with the thinking at the end? Let me check - I had CodeContent that ended with the echo statements and then I continued thinking in the same parameter... Looking at my call, I think I put thinking after the script in the same CodeContent. I need to rewrite the file cleanly.
}
