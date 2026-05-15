FROM python:3.12-slim
WORKDIR /app

COPY requirements.txt pytest.ini ./
RUN pip install --no-cache-dir -r requirements.txt

COPY tests/ tests/

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577

RUN mkdir -p /results
ENTRYPOINT ["pytest", "tests/", "-v", "--junit-xml=/results/junit.xml"]
