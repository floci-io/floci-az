FROM python:3.12-slim
WORKDIR /tests
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577
CMD ["pytest", "tests/", "-v"]
