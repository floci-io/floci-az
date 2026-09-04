using Azure;
using Azure.Storage;
using Azure.Storage.Blobs;
using Azure.Storage.Blobs.Models;
using Azure.Storage.Sas;
using System.Net;
using System.Net.Sockets;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class BlobServiceSasCompatibilityTests
{
    [Test]
    [Timeout(60_000)]
    public async Task ServiceSasReadsAndUploadsWithAccountKey(CancellationToken cancellationToken)
    {
        string endpoint = Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
        // Storage SDKs identify account-in-path endpoints by IP; Docker DNS names use host-style parsing.
        var endpointUri = new UriBuilder(endpoint);
        if (endpointUri.Uri.HostNameType == UriHostNameType.Dns)
        {
            IPAddress[] addresses = await Dns.GetHostAddressesAsync(endpointUri.Host, cancellationToken);
            endpointUri.Host = addresses.First(address => address.AddressFamily == AddressFamily.InterNetwork).ToString();
        }
        var credentials = new StorageSharedKeyCredential("devstoreaccount1",
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==");
        var service = new BlobServiceClient(new Uri($"{endpointUri.Uri.AbsoluteUri.TrimEnd('/')}/devstoreaccount1"), credentials);
        var container = service.GetBlobContainerClient($"dotnet-sas-{Guid.NewGuid():N}");
        await container.CreateAsync(PublicAccessType.None, cancellationToken: cancellationToken);
        try
        {
            var blob = container.GetBlobClient("file.txt");
            await blob.UploadAsync(BinaryData.FromString("original"), cancellationToken);
            Uri readUri = blob.GenerateSasUri(BlobSasPermissions.Read, DateTimeOffset.UtcNow.AddHours(1));
            var signedReader = new BlobClient(readUri);
            var content = await signedReader.DownloadContentAsync(cancellationToken);
            await Assert.That(content.Value.Content.ToString()).IsEqualTo("original");

            var upload = container.GetBlobClient("direct.txt");
            var sas = new BlobSasBuilder
            {
                BlobContainerName = container.Name, BlobName = upload.Name, Resource = "b",
                StartsOn = DateTimeOffset.UtcNow.AddMinutes(-5),
                ExpiresOn = DateTimeOffset.UtcNow.AddHours(1)
            };
            sas.SetPermissions(BlobSasPermissions.Create | BlobSasPermissions.Write);
            await new BlobClient(upload.GenerateSasUri(sas))
                .UploadAsync(BinaryData.FromString("uploaded"), cancellationToken);
            var uploaded = await upload.DownloadContentAsync(cancellationToken);
            await Assert.That(uploaded.Value.Content.ToString()).IsEqualTo("uploaded");

            try
            {
                await signedReader.DeleteAsync(cancellationToken: cancellationToken);
                throw new InvalidOperationException("Read-only SAS unexpectedly authorized deletion");
            }
            catch (RequestFailedException error)
            {
                await Assert.That(error.Status).IsEqualTo(403);
            }
        }
        finally
        {
            await container.DeleteAsync(cancellationToken: cancellationToken);
        }
    }
}
