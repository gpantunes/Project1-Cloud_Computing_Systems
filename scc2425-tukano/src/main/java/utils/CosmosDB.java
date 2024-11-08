package utils;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;

//import io.netty.handler.codec.http.HttpContentEncoder;
import tukano.api.*;
import tukano.api.Result.ErrorCode;

public class CosmosDB {

    private static final String CONNECTION_URL = "https://p1cosmsos.documents.azure.com/"; // replace with your
                                                                                                  // own
    private static final String DB_KEY = "20JeiR6MlWk08rG019R7inhAb1NnkT650YuYHQ2AzrTBE93Y1kYbMY105gZIrWusQ8LYejq97rKDACDbl3tO2w==";
    private static final String DB_NAME = "p1cosmsos";

    private static Logger Log = Logger.getLogger(CosmosDB.class.getName());

    private static CosmosDB instance;
    private CosmosClient client;
    private CosmosDatabase db;
    private CosmosContainer container;
    private String containerName;

    public static synchronized CosmosDB getInstance(String containerName) {
        /*
         * if (instance != null) {
         * return instance;
         * }
         */

        CosmosClient client = new CosmosClientBuilder()
                .endpoint(CONNECTION_URL)
                .key(DB_KEY)
                .directMode()
                // .gatewayMode()
                // replace by .directMode() for better performance
                .consistencyLevel(ConsistencyLevel.SESSION)
                .connectionSharingAcrossClientsEnabled(true)
                .contentResponseOnWriteEnabled(true)
                .buildClient();

        instance = new CosmosDB(client, containerName);

        return instance;

    }

    public CosmosDB(CosmosClient client, String containerName) {
        this.client = client;
        this.containerName = containerName;
    }

    public CosmosContainer getContainer() {
        if (container != null) {
            init();
        }
        return container;
    }

    private synchronized void init() {
        if (db != null) {
            return;
        }
        db = client.getDatabase(DB_NAME);
        Log.info("A db é " + db);
        container = db.getContainer(containerName);
        Log.info("O container é " + container);
    }

    public void close() {
        client.close();
    }

    public <T> Result<T> getOne(String id, Class<T> clazz) {

        return tryCatch(() -> container.readItem(id, new PartitionKey(id), clazz).getItem());
    }

    public <T> Result<?> deleteOne(T obj) {

        return tryCatch(() -> container.deleteItem(obj, new CosmosItemRequestOptions()).getItem());
    }

    public <T> Result<T> updateOne(T obj) {

        return tryCatch(() -> container.upsertItem(obj).getItem());
    }

    public <T> Result<T> insertOne(T obj) {

        Log.info("Nome do container " + containerName);
        return tryCatch(() -> container.createItem(obj).getItem());
    }

    public <T> Result<List<T>> query(String queryStr, Class<T> clazz) {

        return tryCatch(() -> {
            var res = container.queryItems(queryStr, new CosmosQueryRequestOptions(),
                    clazz);
            return res.stream().toList();
        });
    }

    public <T> Result<List<T>> query(Class<T> clazz, String fmt, Object... args) {

        return tryCatch(() -> {
            var res = container.queryItems(String.format(fmt, args), new CosmosQueryRequestOptions(), clazz);
            return res.stream().toList();
        });
    }

    <T> Result<T> tryCatch(Supplier<T> supplierFunc) {
        try {
            init();
            return Result.ok(supplierFunc.get());
        } catch (CosmosException ce) {
            // ce.printStackTrace();
            return Result.error(errorCodeFromStatus(ce.getStatusCode()));
        } catch (Exception x) {
            x.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    static Result.ErrorCode errorCodeFromStatus(int status) {
        return switch (status) {
            case 200 ->
                ErrorCode.OK;
            case 404 ->
                ErrorCode.NOT_FOUND;
            case 409 ->
                ErrorCode.CONFLICT;
            default ->
                ErrorCode.INTERNAL_ERROR;
        };
    }
}
