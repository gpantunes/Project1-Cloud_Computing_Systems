package tukano.impl.storage;

import java.util.function.Consumer;

import tukano.api.Result;

public interface AzureBlobStorage {

    public Result<Void> upload(String filename, byte[] bytes);

    public Result<Void> delete(String filename);

    public Result<byte[]> download(String filename);

    public Result<Void> download(String filename, Consumer<byte[]> sink);
}
