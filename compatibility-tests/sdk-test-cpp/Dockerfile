# Azure SDK for C++ compatibility tests.
#
# Unlike the Python/Node/Java suites, there is no prebuilt package to install: vcpkg
# compiles OpenSSL, libxml2, libcurl and the SDK from source, so the build stage takes
# roughly 6 minutes cold (seconds if warm, since only the final COPY layers change when tests
# are edited). The runtime stage keeps that cost out of the shipped image.
FROM debian:bookworm-slim AS build

# vcpkg is pinned to a release tag for reproducibility. Its SPDX manifest generation
# uses string(JSON ... STRING_ENCODE), which requires CMake 4.3+, so install the
# upstream CMake binary rather than bookworm's 3.25.
ARG CMAKE_VERSION=4.4.2
ARG VCPKG_TAG=2026.07.29

# make + perl are required by vcpkg's openssl port; linux-libc-dev provides the kernel
# headers it checks for.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      g++ \
      git \
      linux-libc-dev \
      make \
      ninja-build \
      perl \
      pkg-config \
      python3 \
      tar \
      unzip \
      zip \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://github.com/Kitware/CMake/releases/download/v${CMAKE_VERSION}/cmake-${CMAKE_VERSION}-linux-$(uname -m).tar.gz" \
      | tar -xz --strip-components=1 -C /usr/local

ENV VCPKG_ROOT=/opt/vcpkg
ENV VCPKG_FORCE_SYSTEM_BINARIES=1
RUN git clone --depth 1 --branch "${VCPKG_TAG}" https://github.com/microsoft/vcpkg.git "${VCPKG_ROOT}" \
    && "${VCPKG_ROOT}/bootstrap-vcpkg.sh" -disableMetrics

WORKDIR /app

# Dependencies first, so editing a test does not rebuild the SDK.
COPY vcpkg.json .
RUN "${VCPKG_ROOT}/vcpkg" install

COPY CMakeLists.txt .
COPY tests/ tests/
RUN cmake -B build -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="${VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake" \
      -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build

# The SDK and its dependencies are static-linked, so the runtime stage needs only
# libstdc++. This keeps the image around 130 MB instead of the ~2.7 GB build stage.
FROM debian:bookworm-slim

COPY --from=build /app/build/compat_tests /usr/local/bin/compat_tests

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577

RUN mkdir -p /results
ENTRYPOINT ["/usr/local/bin/compat_tests", "--gtest_output=xml:/results/junit.xml"]
