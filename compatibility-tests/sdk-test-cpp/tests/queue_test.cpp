#include "emulator_config.hpp"

#include <azure/storage/queues.hpp>
#include <gtest/gtest.h>

#include <string>

using namespace Azure::Storage::Queues;

namespace {

QueueClient Queue(const std::string& name)
{
  return QueueClient::CreateFromConnectionString(floci::QueueConnectionString(), name);
}

} // namespace

// --- Golden path ---

TEST(QueueLifecycle, EnqueueReceiveDelete)
{
  auto queue = Queue("cpp-lifecycle");
  queue.Create();

  queue.EnqueueMessage("hello from C++");

  auto received = queue.ReceiveMessages().Value;
  ASSERT_EQ(received.Messages.size(), 1u);
  EXPECT_EQ(received.Messages[0].MessageText, "hello from C++");

  const auto& message = received.Messages[0];
  queue.DeleteMessage(message.MessageId, message.PopReceipt);

  queue.Delete();
}

TEST(QueueLifecycle, GetPropertiesOnExistingQueueSucceeds)
{
  auto queue = Queue("cpp-properties");
  queue.Create();

  queue.EnqueueMessage("counted");

  auto properties = queue.GetProperties().Value;
  EXPECT_EQ(properties.ApproximateMessageCount, 1);

  queue.Delete();
}

// --- Error paths ---

// Queue error responses are built by a separate cluster of toXmlResponse call sites
// from blob, so these cover that cluster's error codes and XML error bodies.
TEST(QueueErrors, GetPropertiesOnMissingQueueThrowsNotFound)
{
  auto queue = Queue("cpp-no-such-queue");

  try
  {
    queue.GetProperties();
    FAIL() << "expected StorageException for a missing queue";
  }
  catch (const Azure::Storage::StorageException& e)
  {
    EXPECT_EQ(static_cast<int>(e.StatusCode), 404);
    EXPECT_EQ(e.ErrorCode, "QueueNotFound");
  }
}

// GET is allowed a body, so the <Error> document must still be sent and parsed.
TEST(QueueErrors, ReceiveFromMissingQueueParsesXmlErrorBody)
{
  auto queue = Queue("cpp-no-such-queue");

  try
  {
    queue.ReceiveMessages();
    FAIL() << "expected StorageException for a missing queue";
  }
  catch (const Azure::Storage::StorageException& e)
  {
    EXPECT_EQ(static_cast<int>(e.StatusCode), 404);
    EXPECT_EQ(e.ErrorCode, "QueueNotFound");
  }
}
