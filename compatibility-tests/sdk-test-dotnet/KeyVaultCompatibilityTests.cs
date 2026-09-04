using Azure.Core;
using Azure.Core.Pipeline;
using Azure.Extensions.AspNetCore.Configuration.Secrets;
using Azure.Security.KeyVault.Secrets;
using Microsoft.Extensions.Configuration;

namespace FlociAz.Compatibility;

public sealed class KeyVaultCompatibilityTests
{
    private static readonly Uri EmulatorEndpoint = new(
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577");

    [Test]
    [Timeout(60_000)]
    public async Task ListsSecretsAndLoadsConfiguration(CancellationToken cancellationToken)
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

        await Assert.That(client.GetPropertiesOfSecrets(cancellationToken).Any()).IsFalse();
        // Explicit dates isolate routing from the emulator's existing null timestamp incompatibility.
        await client.SetSecretAsync(new KeyVaultSecret("App--Message", "hello")
        {
            Properties =
            {
                NotBefore = DateTimeOffset.UtcNow.AddMinutes(-1),
                ExpiresOn = DateTimeOffset.UtcNow.AddHours(1)
            }
        }, cancellationToken);

        try
        {
            var names = client.GetPropertiesOfSecrets(cancellationToken)
                .Select(secret => secret.Name).ToList();
            await Assert.That(names).IsEquivalentTo(["App--Message"]);

            var asyncNames = new List<string>();
            await foreach (var secret in client.GetPropertiesOfSecretsAsync(cancellationToken))
            {
                asyncNames.Add(secret.Name);
            }
            await Assert.That(asyncNames).IsEquivalentTo(["App--Message"]);

            using var configuration = new ConfigurationManager();
            configuration.AddAzureKeyVault(client, new AzureKeyVaultConfigurationOptions());
            await Assert.That(configuration["App:Message"]).IsEqualTo("hello");
        }
        finally
        {
            await client.StartDeleteSecretAsync("App--Message", cancellationToken);
            await client.PurgeDeletedSecretAsync("App--Message", cancellationToken);
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
