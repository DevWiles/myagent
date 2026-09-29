FROM alpine:latest
RUN sed -i 's#https\?://dl-cdn.alpinelinux.org#https://mirrors.aliyun.com#g' /etc/apk/repositories \
 && apk add --no-cache openjdk17-jre-headless python3 py3-pytest bash git coreutils grep findutils sed ripgrep nodejs npm \
 && npm config set -g registry https://registry.npmmirror.com \
 && npm install -g @anthropic-ai/claude-code \
 && adduser -D -u 1000 bench \
 && mkdir -p /work && chown bench:bench /work
USER bench
ENV HOME=/home/bench
WORKDIR /work
