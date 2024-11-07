package tukano.impl;

import static java.lang.String.format;
import static tukano.api.Result.error;
import static tukano.api.Result.errorOrResult;
import static tukano.api.Result.errorOrValue;
import static tukano.api.Result.errorOrVoid;
import static tukano.api.Result.ok;
import static tukano.api.Result.ErrorCode.BAD_REQUEST;
import static tukano.api.Result.ErrorCode.FORBIDDEN;
//import static tukano.api.Result.ErrorCode.OK;
//import static utils.DB.getOne;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import com.azure.cosmos.CosmosException;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.Jedis;

//import com.azure.cosmos.CosmosContainer;
//import com.azure.cosmos.CosmosException;
//import com.azure.cosmos.models.CosmosItemRequestOptions;
//import com.azure.cosmos.models.CosmosQueryRequestOptions;
//import com.azure.cosmos.models.PartitionKey;

import tukano.api.Blobs;
import tukano.api.Result;
import tukano.api.Result.ErrorCode;
import tukano.api.Short;
import tukano.api.Shorts;
import tukano.api.User;
import tukano.impl.data.Following;
import tukano.impl.data.Likes;
import tukano.impl.rest.TukanoRestServer;
import utils.CosmosDB;
import utils.RedisCache;

public class JavaShorts implements Shorts {

    private static Logger Log = Logger.getLogger(JavaShorts.class.getName());

    private static Shorts instance;

    synchronized public static Shorts getInstance() {
        if (instance == null) {
            instance = new JavaShorts();
        }
        return instance;
    }

    private JavaShorts() {
    }

    @Override
    public Result<Short> createShort(String userId, String password) {
        Log.info(() -> format("createShort : userId = %s, pwd = %s\n", userId, password));

        return errorOrResult(okUser(userId, password), user -> {

            var shortId = format("%s+%s", userId, UUID.randomUUID());

            var blobUrl = format("%s/%s/%s", TukanoRestServer.serverURI, Blobs.NAME,
                    shortId);
            Log.info("Tukano: " + TukanoRestServer.serverURI + " BlobName: " + Blobs.NAME);

            var shrt = new Short(shortId, userId, blobUrl);

            Result<Short> shortDb = CosmosDB.getInstance("shorts").insertOne(shrt);

            if (shortDb.isOK())
                this.putInCache(shrt.getShortId(), shrt.toString());

            return errorOrValue(shortDb,
                    s -> s.copyWithLikes_And_Token(0));
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Short> getShort(String shortId) {
        Log.info(() -> format("getShort : shortId = %s\n", shortId));

        if (shortId == null) {
            return error(BAD_REQUEST);
        }

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query = format("SELECT VALUE COUNT(l.shortId) FROM shorts l WHERE l.shortId = '%s'", shortId);

            Result<List<Long>> like = (Result<List<Long>>) this.queryInCache(query, "likes", jedis,
                    Long.class);

            String dataOnCache = jedis.get(shortId);

            var likes = like.value();

            Log.info("################ tentou sacar do jedis " + dataOnCache);

            if (dataOnCache != null) {
                Log.info("%%%%%%%%%%%%%%%%%%% data on cache nao é null");
                Result<Short> item = errorOrValue(parseShortFromString(dataOnCache),
                        shrt -> shrt.copyWithLikes_And_Token(likes.get(0)));
                return item;
            } else {
                Result<Short> shortRes = errorOrValue(CosmosDB.getInstance("shorts").getOne(shortId, Short.class),
                        shrt -> shrt.copyWithLikes_And_Token(likes.get(0)));
                if (shortRes.isOK()) {
                    Short item = shortRes.value();
                    Log.info("%%%%%%%%%%%%%%%%%%% foi buscar ao cosmos " + item);
                    jedis.set(shortId, item.toString());
                    Log.info("&&&&&&&&&&&&&&&&&& meteu no jedis");
                }
                return shortRes;
            }

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Void> deleteShort(String shortId, String password) {
        Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

        return errorOrResult(getShort(shortId), shrt -> {

            return errorOrResult(okUser(shrt.getOwnerId(), password), user -> {

                // Delete associated likes
                var query1 = format("SELECT * FROM shorts l WHERE l.shortId = '%s'", shortId);
                var result1 = CosmosDB.getInstance("likes").query(query1, Likes.class).value();

                for (Likes l : result1) {
                    Result<Likes> like2 = (Result<Likes>) CosmosDB.getInstance("likes").deleteOne(l);
                    if (like2.isOK())
                        this.delInCache(l.getUserId() + "_" + shortId);

                }

                // Delete the short
                Result<Short> short2 = (Result<Short>) CosmosDB.getInstance("shorts").deleteOne(shrt);

                if (short2.isOK())
                    this.delInCache(shortId);

                // Delete associated blob
                // JavaBlobs.getInstance().delete(shrt.getBlobUrl(), Token.get());
                JavaBlobs.getInstance().delete(shrt.getShortId(), Token.get());

                return ok();

            });
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<List<String>> getShorts(String userId) {
        Log.info(() -> format("getShorts : userId = %s\n", userId));

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query = format("SELECT VALUE s.id FROM shorts s WHERE s.ownerId = '%s'", userId);

            Result<List<String>> data = (Result<List<String>>) this.queryInCache(query, "shorts", jedis,
                    String.class);

            List<String> l = new ArrayList<>();

            for (String str : data.value()) {
                Short s = this.getShort(str).value();
                l.add("ShortId: " + s.getShortId() + " TotalLikes: " + s.getTotalLikes());
            }

            return errorOrValue(okUser(userId), l);

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Void> follow(String userId1, String userId2, boolean isFollowing, String password) {
        Log.info(() -> format("follow : userId1 = %s, userId2 = %s, isFollowing = %s, pwd = %s\n", userId1, userId2,
                isFollowing, password));

        return errorOrResult(okUser(userId1, password), user -> {
            var f = new Following(userId1, userId2);
            Log.info("Cria o objeto follow");

            Result<Void> res = okUser(userId2);

            if (res.isOK()) {

                Result<Following> resDB;

                if (isFollowing) {
                    resDB = CosmosDB.getInstance("followers").insertOne(f);

                    if (resDB.isOK())
                        this.putInCache(userId1 + ":" + userId2, f.toString());

                } else {
                    resDB = (Result<Following>) CosmosDB.getInstance("followers").deleteOne(f);

                    if (resDB.isOK())
                        this.delInCache(userId1 + ":" + userId2);

                }

                return errorOrVoid(res, resDB);
            } else
                return res;
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<List<String>> followers(String userId, String password) {
        Log.info(() -> format("followers : userId = %s, pwd = %s\n", userId, password));

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query = format("SELECT VALUE f.follower FROM followers f WHERE f.followee = '%s'", userId);

            Result<List<String>> data = (Result<List<String>>) this.queryInCache(query, "followers", jedis,
                    String.class);

            return errorOrValue(okUser(userId, password), data);

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Void> like(String shortId, String userId, boolean isLiked, String password) {
        Log.info(() -> format("like : shortId = %s, userId = %s, isLiked = %s, pwd = %s\n", shortId, userId, isLiked,
                password));

        return errorOrResult(getShort(shortId), shrt -> {
            var l = new Likes(userId, shortId, shrt.getOwnerId());
            Log.info("Objeto Like criado");

            Result<User> res = okUser(userId, password);

            if (res.isOK()) {

                Result<Likes> resDB;

                if (isLiked) {
                    resDB = CosmosDB.getInstance("likes").insertOne(l);

                    if (resDB.isOK())
                        this.putInCache(userId + "_" + shortId, l.toString());

                } else {
                    resDB = (Result<Likes>) CosmosDB.getInstance("likes").deleteOne(l);

                    if (resDB.isOK())
                        this.delInCache(userId + "_" + shortId);

                }

                return errorOrVoid(res, resDB);
            } else
                return errorOrVoid(res, res);
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<List<String>> likes(String shortId, String password) {
        Log.info(() -> format("likes : shortId = %s, pwd = %s\n", shortId, password));

        return errorOrResult(getShort(shortId), shrt -> {

            try (Jedis jedis = RedisCache.getCachePool().getResource()) {

                var query = format("SELECT VALUE l.userId FROM likes l WHERE l.shortId = '%s'", shortId);

                Result<List<String>> data = (Result<List<String>>) this.queryInCache(query, "likes", jedis,
                        String.class);

                return errorOrValue(okUser(shrt.getOwnerId(), password), data);

            } catch (CosmosException e) {
                return Result.error(errorCodeFromStatus(e.getStatusCode()));
            } catch (Exception e) {
                e.printStackTrace();
                return Result.error(ErrorCode.INTERNAL_ERROR);
            }

        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<List<String>> getFeed(String userId, String password) {
        Log.info(() -> format("getFeed : userId = %s, pwd = %s\n", userId, password));

        List<String> l = new ArrayList<>();

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);

            Result<List<Short>> data = (Result<List<Short>>) this.queryInCache(query1, "shorts", jedis, Short.class);

            for (Short shrt : data.value())
                l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());

            var query2 = format("SELECT VALUE f.followee FROM followers f WHERE  f.follower = '%s'", userId);

            Result<List<String>> data2 = (Result<List<String>>) this.queryInCache(query2, "followers", jedis,
                    String.class);

            Log.warning("A entrar nos shorts dos meus seguidores");
            for (String s : data2.value()) {
                Log.warning(s);

                var query3 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s' ORDER BY s.timestamp DESC",
                        s);

                Result<List<Short>> data3 = (Result<List<Short>>) this.queryInCache(query3, "shorts", jedis,
                        Short.class);

                for (Short shrt : data3.value())
                    l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());

            }
        }

        return errorOrValue(okUser(userId, password), l);

    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Void> deleteAllShorts(String userId, String password, String token) {
        Log.info(() -> format("deleteAllShorts : userId = %s, password = %s, token = %s\n", userId, password, token));

        if (!Token.isValid(token, userId)) {
            return error(FORBIDDEN);
        }

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            // delete shorts
            Log.warning("Está a tentar apagar os shorts");
            var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);

            Result<List<Short>> data = (Result<List<Short>>) this.queryInCache(query1, "shorts", jedis,
                    Short.class);

            for (Short s : data.value()) {
                Result<Short> shrt = (Result<Short>) CosmosDB.getInstance("shorts").deleteOne(s);
                if (shrt.isOK())
                    jedis.del(s.getShortId());
                Log.warning("Apagou 1 short");
            }

            // delete follows
            Log.warning("Está a tentar apagar os follows");
            var query2 = format("SELECT * FROM followers f WHERE f.follower = '%s' OR f.followee = '%s'", userId,
                    userId);

            Result<List<Following>> data2 = (Result<List<Following>>) this.queryInCache(query2, "followers", jedis,
                    Following.class);

            for (Following f : data2.value()) {
                Result<Following> fol = (Result<Following>) CosmosDB.getInstance("followers").deleteOne(f);
                if (fol.isOK())
                    jedis.del(f.getFollower() + ":" + f.getFollowee());
                Log.warning("Apagou 1 Follow");
            }

            // delete likes
            Log.warning("Está a tentar apagar os likes");

            var query3 = format("SELECT * FROM likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);

            Result<List<Likes>> data3 = (Result<List<Likes>>) this.queryInCache(query3, "likes", jedis,
                    Likes.class);

            for (Likes l : data3.value()) {
                Result<Likes> lik = (Result<Likes>) CosmosDB.getInstance("likes").deleteOne(l);
                if (lik.isOK())
                    jedis.del(l.getUserId() + "_" + l.getShortId());
                Log.warning("Apagou 1 Like");
            }

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

        return ok();

    }

    protected Result<User> okUser(String userId, String pwd) {
        return JavaUsers.getInstance().getUser(userId, pwd);
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

    private Result<Short> parseShortFromString(String shortString) {

        try {
            // Extract values by splitting the string based on known structure
            String shortId = shortString.split("shortId=")[1].split(",")[0].trim();
            String ownerId = shortString.split("ownerId=")[1].split(",")[0].trim();
            String blobUrl = shortString.split("blobUrl=")[1].split(",")[0].trim();
            Long timestamp = Long.parseLong(shortString.split("timestamp=")[1].split(",")[0].trim());
            int totalLikes = Integer.parseInt(shortString.split("totalLikes=")[1].split("]")[0].trim());

            Short shrt = new Short(shortId, ownerId, blobUrl, timestamp, totalLikes);
            return Result.ok(shrt);
        } catch (Exception e) {
            Log.warning("Erro ao transformar o User da cache, que vem como String, para User");
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    private Result<Void> okUser(String userId) {
        var res = okUser(userId, "");
        Log.info(res.error().toString());
        if (res.error() == FORBIDDEN) {
            return ok();
        } else {
            return error(res.error());
        }
    }

    private Result<Void> putInCache(String id, String obj) {
        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            jedis.set(id, obj);
            Log.info("Adicionou objeto à cache");
            return ok();

        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    private Result<Void> delInCache(String id) {

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            jedis.del(id);
            Log.info("Apagou objeto da cache");
            return ok();

        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    private <T> Result<?> queryInCache(String query, String containerName, Jedis jedis, Class<T> clazz) {

        byte[] dataOnCache = jedis.get(String.valueOf(query.hashCode()).getBytes());

        Result<List<T>> data;

        if (dataOnCache == null) {
            data = CosmosDB.getInstance(containerName).query(query, clazz);
            if (data.isOK()) {
                Log.info("Foi buscar os objetos ao CosmosDB e colocou na cache");
                jedis.setex(String.valueOf(query.hashCode()).getBytes(), 20, serialize(data.value()));
            }
        } else
            data = Result.ok(deserializeList(dataOnCache, clazz));

        return data;

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
