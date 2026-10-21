# OPS-036: staff web app for one preview. Build context = frontend/ (FEF-012: pnpm workspace root; the app is apps/staff-web).
# The API URL is baked at build time (Next public env).
#   docker build -f infra/preview/web.Dockerfile --build-arg NEXT_PUBLIC_API_BASE_URL=https://<host>/v1 frontend
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm fetch
COPY . .
RUN pnpm install --offline --frozen-lockfile
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL NEXT_TELEMETRY_DISABLED=1
RUN pnpm --filter staff-web exec next build && pnpm install --offline --frozen-lockfile --prod

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app ./
USER node
WORKDIR /app/apps/staff-web
EXPOSE 3000
CMD ["node_modules/.bin/next", "start", "-p", "3000"]
