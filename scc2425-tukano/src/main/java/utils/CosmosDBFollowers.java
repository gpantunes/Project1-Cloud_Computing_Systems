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
import com.fasterxml.jackson.databind.ObjectMapper;

//import io.netty.handler.codec.http.HttpContentEncoder;
import tukano.api.*;
import tukano.api.Result.ErrorCode;
import tukano.impl.JavaUsers;
import redis.clients.jedis.Jedis;

public class CosmosDBFollowers {

    private static final String CONNECTION_URL = "https://p1cosmos.documents.azure.com:443/"; // replace with your
                                                                                              // own
    private static final String DB_KEY = "wC4GOD5tMZ5f4Xy0lQ7EC2Kd8an916nnHNDbcSCtu47e7JQ0HSjiiQfReZN4ekD6QYGFvStF9DOsACDbEH5N3g==";
    private static final String DB_NAME = "scc2425";

    private static Logger Log = Logger.getLogger(JavaUsers.class.getName());

    // private static String containerName;

    private static final String CONTAINERNAME = "followers";

    private static CosmosDBFollowers instance;
    private CosmosClient client;
    private CosmosDatabase db;
    private CosmosContainer container;

    public static synchronized CosmosDBFollowers getInstance() {
        if (instance != null) {
            return instance;
        }

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

        /*
         * String callOrgininClass =
         * Thread.currentThread().getStackTrace()[2].getClassName();
         * 
         * if (callOrgininClass.toLowerCase().contains("user")) {
         * containerName = "users";
         * 
         * } else
         * containerName = "shorts";
         */
        instance = new CosmosDBFollowers(client);

        return instance;

    }

    public CosmosDBFollowers(CosmosClient client) {
        this.client = client;
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
        container = db.getContainer(CONTAINERNAME);
        Log.info("O container é " + container);
    }

    public void close() {
        client.close();
    }

    public <T> Result<T> getOne(String id, Class<T> clazz) {
        /*
         * try (Jedis jedis = RedisCache.getCachePool().getResource()) {
         * String dataOnCache = jedis.get(id);
         * 
         * T item = null;
         * 
         * if (dataOnCache != null) {
         * item = new ObjectMapper().readValue(dataOnCache, clazz);
         * } else {
         * item = container.readItem(id, new PartitionKey(id), clazz).getItem();
         * jedis.set(id, new ObjectMapper().writeValueAsString(item));
         * }
         * 
         * return Result.ok(item);
         * 
         * } catch (CosmosException e) {
         * return Result.error(errorCodeFromStatus(e.getStatusCode()));
         * } catch (Exception e) {
         * e.printStackTrace();
         * return Result.error(ErrorCode.INTERNAL_ERROR);
         * }
         */

        // T item = container.readItem(id, new PartitionKey(id), clazz).getItem();
        return tryCatch(() -> container.readItem(id, new PartitionKey(id), clazz).getItem());
    }

    public <T> Result<?> deleteOne(T obj) {
        /*
         * try (Jedis jedis = RedisCache.getCachePool().getResource()) {
         * jedis.del(String.valueOf(obj.hashCode()));
         * } catch (Exception e) {
         * e.printStackTrace();
         * return Result.error(ErrorCode.INTERNAL_ERROR);
         * }
         */
        return tryCatch(() -> container.deleteItem(obj, new CosmosItemRequestOptions()).getItem());
    }

    public <T> Result<T> updateOne(T obj) {
        /*
         * try (Jedis jedis = RedisCache.getCachePool().getResource()) {
         * jedis.set(String.valueOf(obj.hashCode()).getBytes(), serialize(obj));
         * } catch (Exception e) {
         * e.printStackTrace();
         * return Result.error(ErrorCode.INTERNAL_ERROR);
         * }
         */
        return tryCatch(() -> container.upsertItem(obj).getItem());
    }

    public <T> Result<T> insertOne(T obj) {
        /*
         * try (Jedis jedis = RedisCache.getCachePool().getResource()) {
         * jedis.set(String.valueOf(obj.hashCode()).getBytes(), serialize(obj));
         * } catch (Exception e) {
         * e.printStackTrace();
         * return Result.error(ErrorCode.INTERNAL_ERROR);
         * }
         */

        Log.info("Nome do container " + CONTAINERNAME);
        return tryCatch(() -> container.createItem(obj).getItem());
    }

    public <T> Result<List<T>> query(String queryStr, Class<T> clazz) {
        /*
         * try (Jedis jedis = RedisCache.getCachePool().getResource()) {
         * byte[] dataOnCache =
         * jedis.get(String.valueOf(queryStr.hashCode()).getBytes());
         * 
         * Result<List<T>> data = Result.ok(deserializeList(dataOnCache, clazz));
         * 
         * if (data == null) {
         * data = tryCatch(() -> {
         * var res = container.queryItems(queryStr, new CosmosQueryRequestOptions(),
         * clazz);
         * return res.stream().toList();
         * });
         * if (data.isOK()) {
         * jedis.setex(dataOnCache, 600, serialize(data));
         * }
         * 
         * }
         * 
         * return data;
         * 
         * } catch (CosmosException e) {
         * return Result.error(errorCodeFromStatus(e.getStatusCode()));
         * } catch (Exception e) {
         * e.printStackTrace();
         * return Result.error(ErrorCode.INTERNAL_ERROR);
         * }
         */

        return tryCatch(() -> {
            var res = container.queryItems(queryStr, new CosmosQueryRequestOptions(),
                    clazz);
            return res.stream().toList();
        });
    }

    public <T> Result<List<T>> query(Class<T> clazz, String fmt, Object... args) {
        /*
         * try (Jedis jedis = RedisCache.getCachePool().getResource()) {
         * byte[] dataOnCache = jedis.get(String.valueOf(String.format(fmt,
         * args).hashCode()).getBytes());
         * 
         * Result<List<T>> data = Result.ok(deserializeList(dataOnCache, clazz));
         * 
         * if (data == null) {
         * data = tryCatch(() -> {
         * var res = container.queryItems(String.format(fmt, args), new
         * CosmosQueryRequestOptions(), clazz);
         * return res.stream().toList();
         * });
         * if (data.isOK()) {
         * jedis.setex(dataOnCache, 600, serialize(data));
         * }
         * 
         * }
         * 
         * return data;
         * 
         * } catch (CosmosException e) {
         * return Result.error(errorCodeFromStatus(e.getStatusCode()));
         * } catch (Exception e) {
         * e.printStackTrace();
         * return Result.error(ErrorCode.INTERNAL_ERROR);
         * }
         */

        return tryCatch(() -> {
            var res = container.queryItems(String.format(fmt, args), new CosmosQueryRequestOptions(), clazz);
            return res.stream().toList();
        });
    }

    private <T> byte[] serialize(T obj) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsBytes(obj); // Serializa o objeto como JSON em byte[]
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private <T> List<T> deserializeList(byte[] data, Class<T> clazz) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            // Convertemos o byte[] em uma lista de objetos do tipo especificado
            return objectMapper.readValue(data,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
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
