package tukano.impl.storage;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.specialized.BlobInputStream;

import tukano.api.Result;
import tukano.impl.JavaShorts;

import static tukano.api.Result.ErrorCode.INTERNAL_ERROR;
import static tukano.api.Result.error;
import static tukano.api.Result.ok;

public class AzureFilesystemStorage implements AzureBlobStorage {

    private static Logger Log = Logger.getLogger(AzureFilesystemStorage.class.getName());

    private static final String BLOBS_CONTAINER_NAME = "shorts";

    @Override
    public Result<Void> upload(String filename, byte[] bytes) {

        // Get connection string in the storage access keys page
        String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=p1sccn;AccountKey=1QWd/3lqlYCq0VQKbK9e7c2TtN46jUQSzeBF0uIyJ3nXNy+ETt/g4yuIAdleODQDHR61wGom4OQ/+AStuJFp2Q==;EndpointSuffix=core.windows.net";

        Log.warning("Vai começar a tentar dar upload");
        try {
            BinaryData data = BinaryData.fromFile(Path.of(filename));

            // Get container client
            BlobContainerClient containerClient = new BlobContainerClientBuilder()
                    .connectionString(storageConnectionString)
                    .containerName(BLOBS_CONTAINER_NAME)
                    .buildClient();

            Log.warning("Criou o cliente");
            // Get client to blob
            BlobClient blob = containerClient.getBlobClient(filename);
            Log.warning("Obteve o blob: " + blob);
            // Upload contents from BinaryData (check documentation for other alternatives)
            blob.upload(data);
            Log.warning("Fez upload");
            System.out.println("File uploaded : " + filename);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return ok();
    }

    @Override
    public Result<Void> delete(String filename) {

        // Get connection string in the storage access keys page
        String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=p1sccn;AccountKey=1QWd/3lqlYCq0VQKbK9e7c2TtN46jUQSzeBF0uIyJ3nXNy+ETt/g4yuIAdleODQDHR61wGom4OQ/+AStuJFp2Q==;EndpointSuffix=core.windows.net";

        try {

            // Get container client
            BlobContainerClient containerClient = new BlobContainerClientBuilder()
                    .connectionString(storageConnectionString)
                    .containerName(BLOBS_CONTAINER_NAME)
                    .buildClient();

            // Get client to blob
            BlobClient blob = containerClient.getBlobClient(filename);

            // Delete the blob contents(check documentation for other alternatives)
            blob.delete();

            System.out.println("File deleted : " + filename);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return ok();
    }

    @Override
    public Result<byte[]> download(String filename) {

        // Get connection string in the storage access keys page
        String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=p1sccn;AccountKey=1QWd/3lqlYCq0VQKbK9e7c2TtN46jUQSzeBF0uIyJ3nXNy+ETt/g4yuIAdleODQDHR61wGom4OQ/+AStuJFp2Q==;EndpointSuffix=core.windows.net";

        byte[] arr = null;

        try {
            // Get container client
            BlobContainerClient containerClient = new BlobContainerClientBuilder()
                    .connectionString(storageConnectionString)
                    .containerName(BLOBS_CONTAINER_NAME)
                    .buildClient();

            // Get client to blob
            BlobClient blob = containerClient.getBlobClient(filename);

            // Download contents to BinaryData (check documentation for other alternatives)
            BinaryData data = blob.downloadContent();

            arr = data.toBytes();

            System.out.println("Blob size : " + arr.length);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return arr != null ? ok(arr) : error(INTERNAL_ERROR);
    }

    @Override
    public Result<Void> download(String filename, Consumer<byte[]> sink) {
        // Connection string from the Azure portal
        String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=p1sccn;AccountKey=1QWd/3lqlYCq0VQKbK9e7c2TtN46jUQSzeBF0uIyJ3nXNy+ETt/g4yuIAdleODQDHR61wGom4OQ/+AStuJFp2Q==;EndpointSuffix=core.windows.net";

        // Define the byte range (start and length) you want to download
        long startRange = 0; // Starting byte position
        int length = 1024; // Number of bytes to read

        try {
            // Get container client
            BlobContainerClient containerClient = new BlobContainerClientBuilder()
                    .connectionString(storageConnectionString)
                    .containerName(BLOBS_CONTAINER_NAME)
                    .buildClient();

            // Get client for the specific blob (file)
            BlobClient blobClient = containerClient.getBlobClient(filename);

            // Open an input stream to the blob
            try (BlobInputStream blobInputStream = blobClient.openInputStream()) {
                // Skip to the starting byte position
                blobInputStream.skip(startRange);

                // Read the specified number of bytes into a buffer
                byte[] buffer = new byte[length];
                int bytesRead = blobInputStream.read(buffer, 0, length);

                // Trim the buffer if fewer bytes were read
                if (bytesRead < length) {
                    buffer = Arrays.copyOf(buffer, bytesRead);
                }

                // Pass the partial data to the sink
                sink.accept(buffer);

                System.out.println("Downloaded partial blob size: " + buffer.length);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return ok();
    }

}
