#include "emulator_config.hpp"

#include <azure/storage/blobs.hpp>
#include <gtest/gtest.h>

#include <cstdint>
#include <string>
#include <vector>

using namespace Azure::Storage::Blobs;

namespace {

BlobServiceClient Service()
{
  return BlobServiceClient::CreateFromConnectionString(floci::BlobConnectionString());
}

BlobContainerClient Container(const std::string& name)
{
  auto container = Service().GetBlobContainerClient(name);
  container.CreateIfNotExists();
  return container;
}

} // namespace

// --- Golden path ---

TEST(BlobLifecycle, UploadDownloadDelete)
{
  auto container = Container("cpp-lifecycle");
  auto blob = container.GetBlockBlobClient("hello.txt");

  const std::string content = "Hello from Azure SDK C++!";
  blob.UploadFrom(reinterpret_cast<const uint8_t*>(content.data()), content.size());

  std::vector<uint8_t> buffer(content.size());
  blob.DownloadTo(buffer.data(), buffer.size());
  EXPECT_EQ(std::string(buffer.begin(), buffer.end()), content);

  blob.Delete();
}

TEST(BlobLifecycle, GetPropertiesOnExistingBlobSucceeds)
{
  auto container = Container("cpp-lifecycle");
  auto blob = container.GetBlockBlobClient("props.txt");

  const std::string content = "properties";
  blob.UploadFrom(reinterpret_cast<const uint8_t*>(content.data()), content.size());

  // Get Blob Properties is a HEAD request. On success it must still carry a
  // Content-Type, which the SDK surfaces as HttpHeaders.ContentType.
  auto properties = blob.GetProperties().Value;
  EXPECT_EQ(properties.BlobSize, static_cast<int64_t>(content.size()));
  EXPECT_FALSE(properties.HttpHeaders.ContentType.empty());

  blob.Delete();
}

// --- Error paths ---

// The regression this suite was added for (floci-io/floci-az#184).
//
// GetProperties is a HEAD request, so its error response cannot carry a body. If the
// emulator still advertises content-type: application/xml on that zero-byte response,
// StorageException::CreateFromResponse hands an empty buffer to XmlReader, which throws
// std::runtime_error("Failed to parse xml.") from inside the RequestFailedException
// constructor, so the exception is never constructed and no catch clause can run.
//
// No other SDK in compatibility-tests/ reproduces this: Python, Java and Node all
// tolerate the bodyless response. In a plain consumer the throw reaches terminate();
// here gtest's handler catches it and reports a failure instead.
TEST(BlobErrors, GetPropertiesOnMissingBlobThrowsNotFound)
{
  auto blob = Container("cpp-errors").GetBlobClient("does-not-exist");

  try
  {
    blob.GetProperties();
    FAIL() << "expected StorageException for a missing blob";
  }
  catch (const Azure::Storage::StorageException& e)
  {
    EXPECT_EQ(static_cast<int>(e.StatusCode), 404);
    // Recovered from the x-ms-error-code header, which must survive even when the
    // body and its content type do not.
    EXPECT_EQ(e.ErrorCode, "BlobNotFound");
  }
}

// Container GetProperties is a GET in the C++ SDK (unlike the blob one above), so this
// covers the container error code and its parsed XML body rather than the HEAD path.
TEST(BlobErrors, GetPropertiesOnMissingContainerThrowsNotFound)
{
  auto container = Service().GetBlobContainerClient("cpp-no-such-container");

  try
  {
    container.GetProperties();
    FAIL() << "expected StorageException for a missing container";
  }
  catch (const Azure::Storage::StorageException& e)
  {
    EXPECT_EQ(static_cast<int>(e.StatusCode), 404);
    EXPECT_EQ(e.ErrorCode, "ContainerNotFound");
  }
}

// The counterpart guard: GET is allowed a body, so the <Error> document and its
// content type must still be sent and parsed. This is what catches an over-broad
// fix that strips Content-Type from every error response.
TEST(BlobErrors, DownloadMissingBlobParsesXmlErrorBody)
{
  auto blob = Container("cpp-errors").GetBlobClient("does-not-exist");

  try
  {
    blob.Download();
    FAIL() << "expected StorageException for a missing blob";
  }
  catch (const Azure::Storage::StorageException& e)
  {
    EXPECT_EQ(static_cast<int>(e.StatusCode), 404);
    EXPECT_EQ(e.ErrorCode, "BlobNotFound");
  }
}

TEST(BlobErrors, CreateExistingContainerThrowsAlreadyExists)
{
  const std::string name = "cpp-already-exists";
  auto container = Container(name);

  try
  {
    container.Create();
    FAIL() << "expected StorageException for an existing container";
  }
  catch (const Azure::Storage::StorageException& e)
  {
    EXPECT_EQ(static_cast<int>(e.StatusCode), 409);
    EXPECT_EQ(e.ErrorCode, "ContainerAlreadyExists");
  }
}
