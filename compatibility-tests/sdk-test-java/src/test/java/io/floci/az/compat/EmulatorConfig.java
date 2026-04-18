package io.floci.az.compat;

public final class EmulatorConfig {

    static final String DEV_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==";
    static final String ACCOUNT = "devstoreaccount1";

    private static final String BASE =
        System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");

    static final String BLOB_CONN = String.format(
        "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;BlobEndpoint=%s/%s;",
        ACCOUNT, DEV_KEY, BASE, ACCOUNT);

    static final String QUEUE_CONN = String.format(
        "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;QueueEndpoint=%s/%s-queue;",
        ACCOUNT, DEV_KEY, BASE, ACCOUNT);

    static final String TABLE_CONN = String.format(
        "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;TableEndpoint=%s/%s-table;",
        ACCOUNT, DEV_KEY, BASE, ACCOUNT);

    private EmulatorConfig() {}
}
