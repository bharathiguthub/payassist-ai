# syntax=docker/dockerfile:1
# Build the SPA, then serve it from unprivileged nginx, which also reverse-proxies BFF paths to
# the backend so the browser sees a single origin (first-party cookies, no CORS).

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
