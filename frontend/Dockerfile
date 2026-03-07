# Use official Node.js 18 image (LTS version)
# "alpine" means a minimal Linux distribution (smaller image size)
FROM node:18-alpine

# Set working directory inside the container
WORKDIR /app

# Copy package.json and package-lock.json first
# Why? Docker caches layers. If these don't change, Docker reuses cached node_modules.
# This makes builds much faster!
COPY package*.json ./

# Install dependencies
# "npm ci" is like "npm install" but:
# - Faster (uses package-lock.json exactly)
# - More reliable (always produces same node_modules)
# - Better for CI/CD and Docker
RUN npm install

# Copy the rest of the source code
COPY public ./public
COPY src ./src
COPY index.html ./index.html
COPY vite.config.mjs ./vite.config.mjs

# Expose port 3000 (Vite dev server)
# This is documentation - tells Docker "this app uses port 3000"
EXPOSE 3000

# Start the development server
# "npm start" runs Vite with host/port from package.json
CMD ["npm", "start"]
