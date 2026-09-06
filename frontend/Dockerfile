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
ENV PORT=8080
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template