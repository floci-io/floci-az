// Shared emulator configuration
#pragma once

#include <cstdlib>
#include <string>

namespace floci {

// The well-known Azurite/devstore development key, same as every other suite.
inline const char* DevKey()
{
  return "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/"
         "K1SZFPTOtr/KBHBeksoGMh0==";
}

inline const char* AccountName() { return "devstoreaccount1"; }

inline std::string Endpoint()
{
  const char* env = std::getenv("FLOCI_AZ_ENDPOINT");
  return env != nullptr ? std::string(env) : std::string("http://localhost:4577");
}

inline std::string BlobConnectionString()
{
  return std::string("DefaultEndpointsProtocol=http;AccountName=") + AccountName()
      + ";AccountKey=" + DevKey() + ";BlobEndpoint=" + Endpoint() + "/" + AccountName() + ";";
}

inline std::string QueueConnectionString()
{
  return std::string("DefaultEndpointsProtocol=http;AccountName=") + AccountName()
      + ";AccountKey=" + DevKey() + ";QueueEndpoint=" + Endpoint() + "/" + AccountName()
      + "-queue;";
}

} // namespace floci
