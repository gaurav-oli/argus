# syntax=docker/dockerfile:1
# Argus frontend — Next.js 16 standalone. Multi-stage; runs the built server
# (not `next dev`). arm64-native on the Mini.
#
# NEXT_PUBLIC_* are inlined at BUILD time. Defaults below match local dev
# (backend on :8080). The Mac Mini deploy overrides them for single-origin
# Tailscale serve — see docs/deploy-runbook.md:
#   --build-arg NEXT_PUBLIC_API_BASE_URL=            (empty → same-origin /api)
#   --build-arg NEXT_PUBLIC_WS_URL=wss://<host>.<tailnet>.ts.net/ws

# ---- deps ----
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ARG NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL} \
    NEXT_PUBLIC_WS_URL=${NEXT_PUBLIC_WS_URL} \
    NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- runtime ----
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
RUN addgroup --system --gid 1001 nodejs && adduser --system --uid 1001 nextjs

# Standalone server + static assets + public/ (per Next's standalone output).
COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
