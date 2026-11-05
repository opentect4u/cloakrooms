# Multi-stage image for the API.
#   docker build -t cloakroom-api .
#   docker build --target migrator -t cloakroom-migrate .   (one-off: npm run migrate && npm run seed)

# ---- build: all deps, compile TypeScript ------------------------------------
FROM node:20-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

# ---- migrator: runs SQL migrations + seeds (needs tsx, a dev dependency) -----
FROM build AS migrator
COPY scripts ./scripts
COPY db ./db
CMD ["sh", "-c", "npm run migrate && npm run seed"]

# ---- runtime: production deps + compiled JS only ------------------------------
FROM node:20-bookworm-slim AS runtime
ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/dist ./dist
# Admin panel templates and static assets (served from disk, not compiled).
COPY views ./views
COPY public ./public
RUN mkdir -p downloads && chown -R node:node /app
USER node
EXPOSE 4000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||4000)+'/api/v1/ready').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/server.js"]
