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

import org.hsqldb.persist.Log;

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
            var blobUrl = format("%s/%s/%s", "https://scc-backend-70735.azurewebsites.net/rest", Blobs.NAME,
                    shortId);
            // var blobUrl = format("%s/%s/%s", TukanoRestServer.serverURI, Blobs.NAME,
            // shortId);
            var shrt = new Short(shortId, userId, blobUrl);

            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                jedis.set(shrt.getShortId(), shrt.toString());
            } catch (Exception e) {
                e.printStackTrace();
                return Result.error(ErrorCode.INTERNAL_ERROR);
            }

            return errorOrValue(CosmosDB.getInstance("shorts").insertOne(shrt),
                    s -> s.copyWithLikes_And_Token(0));
        });
    }

    @Override
    public Result<Short> getShort(String shortId) {
        Log.info(() -> format("getShort : shortId = %s\n", shortId));

        if (shortId == null) {
            return error(BAD_REQUEST);
        }

        Result<List<Long>> like;

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query = format("SELECT VALUE COUNT(l.shortId) FROM shorts l WHERE l.shortId = '%s'", shortId);

            byte[] dataOnCache = jedis.get(String.valueOf(query.hashCode()).getBytes());

            if (dataOnCache == null) {
                like = CosmosDB.getInstance("likes").query(query, Long.class);
                Log.info("Foi buscar os likes à dataBase");
                jedis.setex(String.valueOf(query.hashCode()).getBytes(), 20, serialize(like.value()));
            } else
                like = Result.ok(deserializeList(dataOnCache, Long.class));

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

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
                Short item = shortRes.value();
                Log.info("%%%%%%%%%%%%%%%%%%% foi buscar ao cosmos " + item);
                jedis.set(shortId, item.toString());
                Log.info("&&&&&&&&&&&&&&&&&& meteu no jedis");
                return shortRes;
            }

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

    }

    @Override
    public Result<Void> deleteShort(String shortId, String password) {
        Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

        return errorOrResult(getShort(shortId), shrt -> {

            return errorOrResult(okUser(shrt.getOwnerId(), password), user -> {

                // Delete associated likes
                var query1 = format("SELECT * FROM shorts l WHERE l.shortId = '%s'", shortId);
                var result1 = CosmosDB.getInstance("shorts").query(query1, Likes.class).value();

                for (Likes l : result1)
                    CosmosDB.getInstance("shorts").deleteOne(l);

                // Delete the short
                CosmosDB.getInstance("shorts").deleteOne(shrt);

                try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                    jedis.del(shortId);
                } catch (Exception e) {
                    e.printStackTrace();
                    return Result.error(ErrorCode.INTERNAL_ERROR);
                }

                // Delete associated blob
                JavaBlobs.getInstance().delete(shrt.getBlobUrl(), Token.get());
                // JavaBlobs.getInstance().delete(shrt.getShortId(), Token.get());

                return ok();

            });
        });
    }

    @Override
    public Result<List<String>> getShorts(String userId) {
        Log.info(() -> format("getShorts : userId = %s\n", userId));

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);

            String cacheKey = String.valueOf(query.hashCode());

            byte[] dataOnCache = jedis.get(cacheKey.getBytes());

            Result<List<Short>> data;

            if (dataOnCache == null) {
                data = CosmosDB.getInstance("shorts").query(query, Short.class);
                Log.info("Foi buscar os shorts ao CosmosDB");
                jedis.setex(cacheKey.getBytes(), 20, serialize(data.value()));
            } else
                data = Result.ok(deserializeList(dataOnCache, Short.class));

            List<String> l = new ArrayList<>();

            for (Short s : data.value())
                l.add("ShortId: " + s.getShortId() + " TotalLikes: " + s.getTotalLikes());

            return errorOrValue(okUser(userId), l);

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public Result<Void> follow(String userId1, String userId2, boolean isFollowing, String password) {
        Log.info(() -> format("follow : userId1 = %s, userId2 = %s, isFollowing = %s, pwd = %s\n", userId1, userId2,
                isFollowing, password));

        return errorOrResult(okUser(userId1, password), user -> {
            var f = new Following(userId1, userId2);
            Log.info("Cria o objeto follow");

            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                jedis.set(userId1 + ":" + userId2, f.toString());
                Log.warning("Tudo ok na cache");
            } catch (Exception e) {
                e.printStackTrace();
                Log.warning("Entra no erro da cache");
                return Result.error(ErrorCode.INTERNAL_ERROR);
            }

            Result<Void> u = okUser(userId2);
            if (u.isOK())
                return errorOrVoid(u, isFollowing ? CosmosDB.getInstance("followers").insertOne(f)
                        : CosmosDB.getInstance("followers").deleteOne(f));
            else
                return u;
        });
    }

    @Override
    public Result<List<String>> followers(String userId, String password) {
        Log.info(() -> format("followers : userId = %s, pwd = %s\n", userId, password));

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query = format("SELECT VALUE f.follower FROM followers f WHERE f.followee = '%s'", userId);

            String cacheKey = String.valueOf(query.hashCode());

            byte[] dataOnCache = jedis.get(cacheKey.getBytes());

            Result<List<String>> data;

            if (dataOnCache == null) {
                data = CosmosDB.getInstance("followers").query(query, String.class);
                Log.info("Foi buscar os followers ao CosmosDB");
                jedis.setex(cacheKey.getBytes(), 60, serialize(data.value()));
            } else
                data = Result.ok(deserializeList(dataOnCache, String.class));

            return errorOrValue(okUser(userId, password),
                    data);

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

    }

    @Override
    public Result<Void> like(String shortId, String userId, boolean isLiked, String password) {
        Log.info(() -> format("like : shortId = %s, userId = %s, isLiked = %s, pwd = %s\n", shortId, userId, isLiked,
                password));

        return errorOrResult(getShort(shortId), shrt -> {
            var l = new Likes(userId, shortId, shrt.getOwnerId());
            Log.info("Objeto Like criado");

            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                jedis.set(userId + "_" + shortId, l.toString());
            } catch (Exception e) {
                e.printStackTrace();
                return Result.error(ErrorCode.INTERNAL_ERROR);
            }

            Result<User> u = okUser(userId, password);
            if (u.isOK())
                return errorOrVoid(u, isLiked ? CosmosDB.getInstance("likes").insertOne(l)
                        : CosmosDB.getInstance("likes").deleteOne(l));
            else
                return errorOrVoid(u, u);
        });
    }

    @Override
    public Result<List<String>> likes(String shortId, String password) {
        Log.info(() -> format("likes : shortId = %s, pwd = %s\n", shortId, password));

        return errorOrResult(getShort(shortId), shrt -> {

            try (Jedis jedis = RedisCache.getCachePool().getResource()) {

                var query = format("SELECT VALUE l.userId FROM likes l WHERE l.shortId = '%s'", shortId);

                byte[] dataOnCache = jedis.get(String.valueOf(query.hashCode()).getBytes());

                Result<List<String>> data;

                if (dataOnCache == null) {
                    data = CosmosDB.getInstance("likes").query(query, String.class);
                    Log.info("Foi buscar os likes ao CosmosDB");
                    jedis.setex(String.valueOf(query.hashCode()).getBytes(), 60, serialize(data.value()));
                } else
                    data = Result.ok(deserializeList(dataOnCache, String.class));

                return errorOrValue(okUser(shrt.getOwnerId(), password), data);

            } catch (CosmosException e) {
                return Result.error(errorCodeFromStatus(e.getStatusCode()));
            } catch (Exception e) {
                e.printStackTrace();
                return Result.error(ErrorCode.INTERNAL_ERROR);
            }

        });
    }

    @Override
    public Result<List<String>> getFeed(String userId, String password) {
        Log.info(() -> format("getFeed : userId = %s, pwd = %s\n", userId, password));

        List<String> l = new ArrayList<>();

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);

            byte[] dataOnCache = jedis.get(String.valueOf(query1.hashCode()).getBytes());

            Result<List<Short>> data;

            if (dataOnCache == null) {
                data = CosmosDB.getInstance("shorts").query(query1, Short.class);
                Log.info("Foi buscar os shorts ao CosmosDB");
                jedis.setex(String.valueOf(query1.hashCode()).getBytes(), 60, serialize(data.value()));
            } else
                data = Result.ok(deserializeList(dataOnCache, Short.class));

            for (Short shrt : data.value())
                l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {
            var query2 = format("SELECT VALUE f.followee FROM followers f WHERE  f.follower = '%s'", userId);

            byte[] dataOnCache = jedis.get(String.valueOf(query2.hashCode()).getBytes());

            Result<List<String>> data;

            if (dataOnCache == null) {
                data = CosmosDB.getInstance("followers").query(query2, String.class);
                Log.info("Foi buscar os followes ao CosmosDB");
                jedis.setex(String.valueOf(query2.hashCode()).getBytes(), 60, serialize(data.value()));
            } else
                data = Result.ok(deserializeList(dataOnCache, String.class));

            Log.warning("A entrar nos shorts dos meus seguidores");
            for (String s : data.value()) {
                Log.warning(s);

                try (Jedis jedis2 = RedisCache.getCachePool().getResource()) {

                    var query3 = format(
                            "SELECT * FROM shorts s WHERE s.ownerId = '%s' ORDER BY s.timestamp DESC",
                            s);

                    String cacheKey = String.valueOf(query3.hashCode());

                    byte[] dataOnCache2 = jedis2.get(cacheKey.getBytes());

                    Result<List<Short>> data2;

                    if (dataOnCache2 == null) {
                        data2 = CosmosDB.getInstance("shorts").query(query3, Short.class);
                        Log.info("Foi buscar os shorts dos followes ao CosmosDB");
                        jedis2.setex(cacheKey.getBytes(), 20, serialize(data2.value()));
                    } else
                        data2 = Result.ok(deserializeList(dataOnCache2, Short.class));

                    for (Short shrt : data2.value())
                        l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());

                } catch (CosmosException e) {
                    return Result.error(errorCodeFromStatus(e.getStatusCode()));
                } catch (Exception e) {
                    e.printStackTrace();
                    return Result.error(ErrorCode.INTERNAL_ERROR);
                }

            }

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

        return errorOrValue(okUser(userId, password), l);

    }

    @Override
    public Result<Void> deleteAllShorts(String userId, String password, String token) {
        Log.info(() -> format("deleteAllShorts : userId = %s, password = %s, token = %s\n", userId, password, token));

        if (!Token.isValid(token, userId)) {
            return error(FORBIDDEN);
        }

        Log.warning("Está a tentar apagar os shorts");

        // delete shorts
        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);

            String cacheKey = String.valueOf(query1.hashCode());

            byte[] dataOnCache = jedis.get(cacheKey.getBytes());

            Result<List<Short>> data;

            if (dataOnCache == null) {
                data = CosmosDB.getInstance("shorts").query(query1, Short.class);
                Log.info("Foi buscar os shorts ao CosmosDB");
                jedis.setex(cacheKey.getBytes(), 20, serialize(data.value()));
            } else
                data = Result.ok(deserializeList(dataOnCache, Short.class));

            for (Short s : data.value()) {
                CosmosDB.getInstance("shorts").deleteOne(s);
                jedis.del(s.getShortId());
                Log.warning("Apagou 1 short");
            }

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

        Log.warning("Está a tentar apagar os follows");

        // delete follows
        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query2 = format("SELECT * FROM followers f WHERE f.follower = '%s' OR f.followee = '%s'", userId,
                    userId);

            String cacheKey = String.valueOf(query2.hashCode());

            byte[] dataOnCache = jedis.get(cacheKey.getBytes());

            Result<List<Following>> data;

            if (dataOnCache == null) {
                data = CosmosDB.getInstance("followers").query(query2, Following.class);
                Log.info("Foi buscar os followers ao CosmosDB");
                jedis.setex(cacheKey.getBytes(), 20, serialize(data.value()));
            } else
                data = Result.ok(deserializeList(dataOnCache, Following.class));

            for (Following f : data.value()) {
                CosmosDB.getInstance("followers").deleteOne(f);
                jedis.del(f.getFollower() + ":" + f.getFollowee());
                Log.warning("Apagou 1 Follow");
            }

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

        Log.warning("Está a tentar apagar os likes");

        // delete likes
        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            var query3 = format("SELECT * FROM likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);

            String cacheKey = String.valueOf(query3.hashCode());

            byte[] dataOnCache = jedis.get(cacheKey.getBytes());

            Result<List<Likes>> data;

            if (dataOnCache == null) {
                data = CosmosDB.getInstance("likes").query(query3, Likes.class);
                Log.info("Foi buscar os likes ao CosmosDB");
                jedis.setex(cacheKey.getBytes(), 20, serialize(data.value()));
            } else
                data = Result.ok(deserializeList(dataOnCache, Likes.class));

            for (Likes l : data.value()) {
                CosmosDB.getInstance("likes").deleteOne(l);
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
