# OPS-036: staff web app for one preview. Build context = frontend/. The API URL is baked at build time (Next public env).
#   docker build -f infra/preview/web.Dockerfile --build-arg NEXT_PUBLIC_API_BASE_URL=https://<host>/v1 frontend
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL NEXT_TELEMETRY_DISABLED=1
RUN npx next build && npm prune --omit=dev

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app ./
USER node
EXPOSE 3000
CMD ["npx", "next", "start", "-p", "3000"]
