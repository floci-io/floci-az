cmake_minimum_required(VERSION 3.22)
project(floci-sdk-test-cpp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(azure-storage-blobs-cpp CONFIG REQUIRED)
find_package(azure-storage-queues-cpp CONFIG REQUIRED)
find_package(GTest CONFIG REQUIRED)

enable_testing()

add_executable(compat_tests
  tests/blob_test.cpp
  tests/queue_test.cpp
)

target_include_directories(compat_tests PRIVATE tests)

target_link_libraries(compat_tests PRIVATE
  Azure::azure-storage-blobs
  Azure::azure-storage-queues
  GTest::gtest
  GTest::gtest_main
)
