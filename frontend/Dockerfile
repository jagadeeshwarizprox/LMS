FROM node:20-alpine AS build
WORKDIR /src
COPY package*.json ./
RUN npm ci --no-audit --no-fund

# Vite inlines this at build time; it cannot be changed at runtime.
ARG VITE_API_BASE
ENV VITE_API_BASE=$VITE_API_BASE

COPY . .
RUN npm run build

FROM nginx:alpine
# stock nginx allows 1024 connections per worker; a proxied request holds two, so that
# tops out near 500 concurrent requests. Sized for 1,000 active users plus keepalive.
RUN sed -i 's/worker_connections\s*[0-9]*;/worker_connections 4096;/' /etc/nginx/nginx.conf \
 && sed -i '/^worker_processes/a worker_rlimit_nofile 8192;' /etc/nginx/nginx.conf \
 && grep -q 'worker_connections 4096;' /etc/nginx/nginx.conf \
 && grep -q '^worker_rlimit_nofile 8192;' /etc/nginx/nginx.conf
ENV PORT=8080
# where /api/ is forwarded. In docker compose the API service is called "api".
# Hosting the API elsewhere: set this to its base URL, e.g. https://api.example.com
ENV API_UPSTREAM=http://api:8080
# DNS server nginx uses to find the API. 127.0.0.11 is Docker's own; elsewhere use
# the host's resolver (for example 8.8.8.8, or the platform's internal DNS)
ENV NGINX_RESOLVER=127.0.0.11
# only these are substituted, so nginx's own $variables are left alone
ENV NGINX_ENVSUBST_FILTER="^(PORT|API_UPSTREAM|NGINX_RESOLVER)$"
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:${PORT}/ || exit 1
