using Azure.Core;
using Azure.Core.Pipeline;
using Azure.Security.KeyVault.Certificates;
using Azure.Security.KeyVault.Secrets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace FlociAz.Compatibility;

public sealed class KeyVaultCertificateCompatibilityTests
{
    private static readonly Uri Endpoint = new(Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577");

    [Test]
    [Timeout(60_000)]
    public async Task CreatesAndRotatesSigningCertificatesWithMatchingPfxSecrets(CancellationToken cancellationToken)
    {
        string account = $"cert{Guid.NewGuid():N}";
        using var http = new HttpClient(new EmulatorHandler(account));
        var transport = new HttpClientTransport(http);
        var vault = new UriBuilder(Endpoint) { Scheme = "https", Path = $"/{account}-keyvault" }.Uri;
        var certificates = new CertificateClient(vault, new FakeCredential(), new CertificateClientOptions
        {
            Transport = transport,
            DisableChallengeResourceVerification = true
        });
        var secrets = new SecretClient(vault, new FakeCredential(), new SecretClientOptions
        {
            Transport = transport,
            DisableChallengeResourceVerification = true
        });
        var policy = new CertificatePolicy("Self", "CN=PalCal Identity Server Signing Certificate")
        {
            KeyType = CertificateKeyType.Rsa,
            KeySize = 2048,
            ReuseKey = false,
            Exportable = true,
            ContentType = CertificateContentType.Pkcs12,
            ValidityInMonths = 24,
            KeyUsage = { CertificateKeyUsage.DigitalSignature, CertificateKeyUsage.KeyEncipherment }
        };
        var create = await certificates.StartCreateCertificateAsync("signing", policy, cancellationToken: cancellationToken);
        var first = (await create.WaitForCompletionAsync(cancellationToken)).Value;
        var secret = (await secrets.GetSecretAsync("signing", first.Properties.Version, cancellationToken)).Value;
        using var pfx = X509CertificateLoader.LoadPkcs12(Convert.FromBase64String(secret.Value), null,
            X509KeyStorageFlags.EphemeralKeySet | X509KeyStorageFlags.Exportable);
        await Assert.That(pfx.HasPrivateKey).IsTrue();
        await Assert.That(pfx.RawData).IsEquivalentTo(first.Cer);
        using var privateKey = pfx.GetRSAPrivateKey()!;
        using var publicKey = pfx.GetRSAPublicKey()!;
        byte[] payload = "certificate compatibility"u8.ToArray();
        byte[] signature = privateKey.SignData(payload, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        await Assert.That(publicKey.VerifyData(payload, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)).IsTrue();

        var rotate = await certificates.StartCreateCertificateAsync("signing", policy, cancellationToken: cancellationToken);
        var second = (await rotate.WaitForCompletionAsync(cancellationToken)).Value;
        await Assert.That(second.Properties.Version).IsNotEqualTo(first.Properties.Version);
        var versions = new List<string>();
        await foreach (var version in certificates.GetPropertiesOfCertificateVersionsAsync("signing", cancellationToken: cancellationToken))
        {
            versions.Add(version.Version);
        }
        await Assert.That(versions).IsEquivalentTo([first.Properties.Version, second.Properties.Version]);
        var previous = (await certificates.GetCertificateVersionAsync("signing", first.Properties.Version, cancellationToken)).Value;
        await Assert.That(previous.Cer).IsEquivalentTo(first.Cer);

        var delete = await certificates.StartDeleteCertificateAsync("signing", cancellationToken);
        await delete.WaitForCompletionAsync(cancellationToken);
        var recovery = await certificates.StartRecoverDeletedCertificateAsync("signing", cancellationToken);
        await recovery.WaitForCompletionAsync(cancellationToken);
        var recovered = (await certificates.GetCertificateAsync("signing", cancellationToken)).Value;
        await Assert.That(recovered.Properties.Version).IsEqualTo(second.Properties.Version);
        await secrets.GetSecretAsync("signing", first.Properties.Version, cancellationToken);
        await certificates.StartDeleteCertificateAsync("signing", cancellationToken);
        await certificates.PurgeDeletedCertificateAsync("signing", cancellationToken);
    }

    private sealed class FakeCredential : TokenCredential
    {
        public override AccessToken GetToken(TokenRequestContext context, CancellationToken cancellationToken) =>
            new("certificate-test-token", DateTimeOffset.UtcNow.AddHours(1));
        public override ValueTask<AccessToken> GetTokenAsync(TokenRequestContext context, CancellationToken cancellationToken) =>
            ValueTask.FromResult(GetToken(context, cancellationToken));
    }

    private sealed class EmulatorHandler(string account) : DelegatingHandler(new HttpClientHandler())
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var uri = request.RequestUri!;
            string prefix = $"/{account}-keyvault";
            request.RequestUri = new UriBuilder(uri)
            {
                Scheme = Endpoint.Scheme,
                Host = Endpoint.Host,
                Port = Endpoint.Port,
                Path = uri.AbsolutePath.StartsWith(prefix, StringComparison.Ordinal) ? uri.AbsolutePath : prefix + uri.AbsolutePath
            }.Uri;
            return base.SendAsync(request, cancellationToken);
        }
    }
}
