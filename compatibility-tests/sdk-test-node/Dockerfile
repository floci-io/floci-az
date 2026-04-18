FROM node:22-slim
WORKDIR /app

COPY package*.json ./
RUN npm install --no-fund --no-audit

COPY tsconfig.json jest.config.js ./
COPY tests/ tests/

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577
ENV JEST_JUNIT_OUTPUT_DIR=/results
ENV JEST_JUNIT_OUTPUT_NAME=junit.xml

RUN mkdir -p /results
ENTRYPOINT ["npm", "test"]
