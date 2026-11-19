# ---- build stage: fetch Node dependencies ----
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# ---- runtime: a real Ubuntu system ----
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl wget git nano vim less sudo locales tzdata \
      bash-completion iputils-ping net-tools procps psmisc htop unzip zip \
      openssh-client python3 python3-pip man-db tini \
 && locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8

# Node and the app live in /app, outside the persistent volumes, so that
# rebuilding the image updates Termant without touching your Ubuntu system.
COPY --from=build /usr/local/bin/node /app/bin/node
COPY --from=build /app/node_modules /app/node_modules
COPY package.json server.js /app/
COPY public /app/public
COPY docker/bashrc /root/.bashrc

ENV HOME=/root PORT=3000
WORKDIR /root
EXPOSE 3000
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/app/bin/node", "/app/server.js"]
