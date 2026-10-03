using Azure.Core;
using Azure.Core.Pipeline;
using Azure.Security.KeyVault.Keys;

namespace FlociAz.Compatibility;

public sealed class KeyVaultKeysCompatibilityTests
{
    private static readonly Uri EmulatorEndpoint = new(
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577");

    [Test]
    [Timeout(60_000)]
    public async Task ListKeysAndDeletedKeys(CancellationToken cancellationToken)
    {
        using var httpClient = new HttpClient(new EmulatorHandler());
        var client = CreateClient(httpClient);

        await client.CreateRsaKeyAsync(new CreateRsaKeyOptions("list-test"), cancellationToken);
        try
        {
            // The SDK sends GET keys/ with a trailing slash.
            var names = new List<string>();
            await foreach (var properties in client.GetPropertiesOfKeysAsync(cancellationToken))
            {
                names.Add(properties.Name);
            }
            await Assert.That(names).Contains("list-test");

            await client.StartDeleteKeyAsync("list-test", cancellationToken);
            var deleted = new List<string>();
            await foreach (var key in client.GetDeletedKeysAsync(cancellationToken))
            {
                deleted.Add(key.Name);
            }
            await Assert.That(deleted).Contains("list-test");
        }
        finally
        {
            await client.PurgeDeletedKeyAsync("list-test", cancellationToken);
        }
    }

    [Test]
    [Arguments(false, false)]
    [Arguments(true, false)]
    [Arguments(false, true)]
    [Arguments(true, true)]
    [Timeout(60_000)]
    public async Task OptionalTimestampsSurviveKeyLifecycle(
        bool includeNotBefore, bool includeExpiresOn, CancellationToken cancellationToken)
    {
        using var httpClient = new HttpClient(new EmulatorHandler());
        var client = CreateClient(httpClient);
        DateTimeOffset? notBefore = includeNotBefore ? DateTimeOffset.FromUnixTimeSeconds(1700000000) : null;
        DateTimeOffset? expiresOn = includeExpiresOn ? DateTimeOffset.FromUnixTimeSeconds(1900000000) : null;

        var created = await client.CreateRsaKeyAsync(
            new CreateRsaKeyOptions("timestamp-test") { NotBefore = notBefore, ExpiresOn = expiresOn },
            cancellationToken);
        try
        {
            await CheckDates(created.Value.Properties);
            var fetched = await client.GetKeyAsync("timestamp-test", cancellationToken: cancellationToken);
            await CheckDates(fetched.Value.Properties);
            var version = await client.GetKeyAsync("timestamp-test", created.Value.Properties.Version,
                cancellationToken);
            await CheckDates(version.Value.Properties);
            created.Value.Properties.Tags["updated"] = "true";
            var updated = await client.UpdateKeyPropertiesAsync(created.Value.Properties,
                cancellationToken: cancellationToken);
            await CheckDates(updated.Value.Properties);
            await foreach (var properties in client.GetPropertiesOfKeyVersionsAsync("timestamp-test", cancellationToken))
            {
                await CheckDates(properties);
            }
            var deleteOperation = await client.StartDeleteKeyAsync("timestamp-test", cancellationToken);
            await CheckDates(deleteOperation.Value.Properties);
            var deleted = await client.GetDeletedKeyAsync("timestamp-test", cancellationToken);
            await CheckDates(deleted.Value.Properties);
            var recovered = await client.StartRecoverDeletedKeyAsync("timestamp-test", cancellationToken);
            await recovered.WaitForCompletionAsync(cancellationToken);
            await CheckDates(recovered.Value.Properties);
        }
        finally
        {
            await client.StartDeleteKeyAsync("timestamp-test", cancellationToken);
            await client.PurgeDeletedKeyAsync("timestamp-test", cancellationToken);
        }

        async Task CheckDates(KeyProperties properties)
        {
            await Assert.That(properties.NotBefore).IsEqualTo(notBefore);
            await Assert.That(properties.ExpiresOn).IsEqualTo(expiresOn);
            await Assert.That(properties.CreatedOn).IsNotNull();
            await Assert.That(properties.UpdatedOn).IsNotNull();
        }
    }

    private static KeyClient CreateClient(HttpClient httpClient)
    {
        var vaultUri = new UriBuilder(EmulatorEndpoint)
        {
            Scheme = "https",
            Path = $"/kv{Guid.NewGuid():N}-keyvault"
        };
        return new KeyClient(vaultUri.Uri, new FakeCredential(), new KeyClientOptions
        {
            DisableChallengeResourceVerification = true,
            Transport = new HttpClientTransport(httpClient)
        });
    }

    private sealed class FakeCredential : TokenCredential
    {
        public override AccessToken GetToken(
            TokenRequestContext requestContext, CancellationToken cancellationToken) =>
            new("fake-token-for-local-emulator", DateTimeOffset.UtcNow.AddHours(1));

        public override ValueTask<AccessToken> GetTokenAsync(
            TokenRequestContext requestContext, CancellationToken cancellationToken) =>
            ValueTask.FromResult(GetToken(requestContext, cancellationToken));
    }

    // Keep HTTPS in the SDK pipeline for challenge authentication; use the configured
    // emulator scheme only at the transport boundary, as in the Python compat suite.
    private sealed class EmulatorHandler() : DelegatingHandler(new HttpClientHandler())
    {
        protected override HttpResponseMessage Send(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            RewriteUri(request);
            return base.Send(request, cancellationToken);
        }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            RewriteUri(request);
            return base.SendAsync(request, cancellationToken);
        }

        private static void RewriteUri(HttpRequestMessage request)
        {
            request.RequestUri = new UriBuilder(request.RequestUri!)
            {
                Scheme = EmulatorEndpoint.Scheme,
                Port = EmulatorEndpoint.Port
            }.Uri;
        }
    }
}
