FROM --platform=linux/amd64 python:3.12-slim
WORKDIR /tests
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
ENV EVENTHUB_HOST=localhost
ENV EVENTHUB_AMQP_PORT=5672
CMD ["pytest", "tests/", "-v"]
