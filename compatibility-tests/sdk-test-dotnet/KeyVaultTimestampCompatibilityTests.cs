using Azure.Core;
using Azure.Core.Pipeline;
using Azure.Security.KeyVault.Secrets;

namespace FlociAz.Compatibility;

public sealed class KeyVaultTimestampCompatibilityTests
{
    private static readonly Uri EmulatorEndpoint = new(
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577");

    [Test]
    [Arguments(false, false)]
    [Arguments(true, false)]
    [Arguments(false, true)]
    [Arguments(true, true)]
    [Timeout(60_000)]
    public async Task OptionalTimestampsSurviveSecretLifecycle(
        bool includeNotBefore, bool includeExpiresOn, CancellationToken cancellationToken)
    {
        using var httpClient = new HttpClient(new EmulatorHandler());
        var vaultUri = new UriBuilder(EmulatorEndpoint)
        {
            Scheme = "https",
            Path = $"/kv{Guid.NewGuid():N}-keyvault"
        };
        var client = new SecretClient(vaultUri.Uri, new FakeCredential(), new SecretClientOptions
        {
            DisableChallengeResourceVerification = true,
            Transport = new HttpClientTransport(httpClient)
        });
        DateTimeOffset? notBefore = includeNotBefore ? DateTimeOffset.FromUnixTimeSeconds(1700000000) : null;
        DateTimeOffset? expiresOn = includeExpiresOn ? DateTimeOffset.FromUnixTimeSeconds(1900000000) : null;
        var secret = new KeyVaultSecret("timestamp-test", "hello")
        {
            Properties = { NotBefore = notBefore, ExpiresOn = expiresOn }
        };
        var created = await client.SetSecretAsync(secret, cancellationToken);
        try
        {
            await CheckDates(created.Value.Properties);
            var fetched = await client.GetSecretAsync("timestamp-test", cancellationToken: cancellationToken);
            await CheckDates(fetched.Value.Properties);
            var version = await client.GetSecretAsync("timestamp-test", created.Value.Properties.Version,
                cancellationToken: cancellationToken);
            await CheckDates(version.Value.Properties);
            created.Value.Properties.ContentType = "text/plain";
            var updated = await client.UpdateSecretPropertiesAsync(created.Value.Properties, cancellationToken);
            await CheckDates(updated.Value);
            await foreach (var properties in client.GetPropertiesOfSecretVersionsAsync("timestamp-test", cancellationToken))
            {
                await CheckDates(properties);
            }
            await client.StartDeleteSecretAsync("timestamp-test", cancellationToken);
            var deleted = await client.GetDeletedSecretAsync("timestamp-test", cancellationToken);
            await CheckDates(deleted.Value.Properties);
            var recovered = await client.StartRecoverDeletedSecretAsync("timestamp-test", cancellationToken);
            await recovered.WaitForCompletionAsync(cancellationToken);
            await CheckDates(recovered.Value);
        }
        finally
        {
            await client.StartDeleteSecretAsync("timestamp-test", cancellationToken);
            await client.PurgeDeletedSecretAsync("timestamp-test", cancellationToken);
        }

        async Task CheckDates(SecretProperties properties)
        {
            await Assert.That(properties.NotBefore).IsEqualTo(notBefore);
            await Assert.That(properties.ExpiresOn).IsEqualTo(expiresOn);
            await Assert.That(properties.CreatedOn).IsNotNull();
            await Assert.That(properties.UpdatedOn).IsNotNull();
        }
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
