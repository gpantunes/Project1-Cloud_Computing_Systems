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
import utils.DB;
import utils.RedisCache;

public class JavaShorts implements Shorts {

    private static Logger Log = Logger.getLogger(JavaShorts.class.getName());

    private static Shorts instance;

    // flags para definir o que se vai utilizar
    private static final boolean cacheOn = TukanoRestServer.cacheOn;
    private static final boolean sqlOn = TukanoRestServer.sqlOn;

    //Gets instances for the different containers
    private static final CosmosDB CosmosDBShorts = CosmosDB.getInstance("shorts");
    private static final CosmosDB CosmosDBFollowers = CosmosDB.getInstance("followers");
    private static final CosmosDB CosmosDBLikes = CosmosDB.getInstance("likes");

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

            Result<Short> shortDb;

            if (sqlOn)
                shortDb = DB.insertOne(shrt);
            else
                shortDb = CosmosDBShorts.insertOne(shrt);

            if (shortDb.isOK() && cacheOn)
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

        Result<Short> shortRes;
        Result<List<Long>> like;

        var query = format("SELECT VALUE COUNT(l.shortId) FROM likes l WHERE l.shortId = '%s'", shortId);

        if (sqlOn)
            query = format("SELECT COUNT(l.shortId) FROM \"likes\" l WHERE l.shortId = '%s'", shortId);

        like = (Result<List<Long>>) this.tryQuery(query, "likes",
                Long.class);

        if (cacheOn) {

            shortRes = this.getFromCache(shortId, like);

        } else {

            if (sqlOn) {

                shortRes = errorOrValue(DB.getOne(shortId, Short.class),
                        shrt -> shrt.copyWithLikes_And_Token(like.value().get(0)));

            } else {

                shortRes = errorOrValue(CosmosDBShorts.getOne(shortId, Short.class),
                        shrt -> shrt.copyWithLikes_And_Token(like.value().get(0)));

            }
        }
        return shortRes;

    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Void> deleteShort(String shortId, String password) {
        Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

        return errorOrResult(this.getShort(shortId), shrt -> {

            return errorOrResult(okUser(shrt.getOwnerId(), password), user -> {

                if (sqlOn) {
                    return DB.transaction(hibernate -> {

                        hibernate.remove(shrt);
                        var query = format("DELETE FROM \"likes\" l WHERE l.shortId = '%s'", shortId);
                        hibernate.createNativeQuery(query, Likes.class).executeUpdate();

                    });
                } else {

                    var query = format("SELECT * FROM likes l WHERE l.shortId = '%s'", shortId);

                    if (sqlOn)
                        query = format("SELECT * FROM \"likes\" l WHERE l.shortId = '%s'", shortId);

                    Result<List<Likes>> result1 = (Result<List<Likes>>) this.tryQuery(query, "likes", Likes.class);

                    for (Likes l : result1.value()) {
                        Result<Likes> like2 = (Result<Likes>) CosmosDBLikes.deleteOne(l);

                        if (like2.isOK() && cacheOn)
                            this.delInCache(l.getUserId() + "_" + shortId);
                    }

                    // Delete the short
                    CosmosDBShorts.deleteOne(shrt);
                }

                if (cacheOn)
                    this.delInCache(shortId);

                // Delete associated blob
                JavaBlobs.getInstance().delete(shrt.getShortId(), Token.get());

                return ok();

            });
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<List<String>> getShorts(String userId) {
        Log.info(() -> format("getShorts : userId = %s\n", userId));

        List<String> dataDB;
        List<String> l = new ArrayList<>();

        var query = format("SELECT VALUE s.id FROM shorts s WHERE s.ownerId = '%s'", userId);

        if (sqlOn)
            query = format("SELECT s.shortId FROM \"short\" s WHERE s.ownerId = '%s'", userId);

        Result<List<String>> data = (Result<List<String>>) this.tryQuery(query, "shorts",
                String.class);

        for (String str : data.value()) {
            Short s = this.getShort(str).value();
            l.add("ShortId: " + s.getShortId() + " TotalLikes: " + s.getTotalLikes());
        }

        return errorOrValue(okUser(userId), l);

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

                    if (sqlOn)
                        resDB = DB.insertOne(f);
                    else
                        resDB = CosmosDBFollowers.insertOne(f);

                    if (resDB.isOK() && cacheOn)
                        this.putInCache(userId1 + ":" + userId2, f.toString());

                } else {
                    if (sqlOn)
                        resDB = DB.deleteOne(f);
                    else
                        resDB = (Result<Following>) CosmosDBFollowers.deleteOne(f);

                    if (resDB.isOK() && cacheOn)
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

        var query = format("SELECT VALUE f.follower FROM followers f WHERE f.followee = '%s'", userId);

        if (sqlOn)
            query = format("SELECT f.follower FROM \"followers\" f WHERE f.followee = '%s'", userId);

        Result<List<String>> data = (Result<List<String>>) this.tryQuery(query, "followers",
                String.class);

        return errorOrValue(okUser(userId, password), data);

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
                    if (sqlOn)
                        resDB = DB.insertOne(l);
                    else
                        resDB = CosmosDBLikes.insertOne(l);

                    if (resDB.isOK())
                        this.putInCache(userId + "_" + shortId, l.toString());

                } else {

                    if (sqlOn)
                        resDB = DB.deleteOne(l);
                    else
                        resDB = (Result<Likes>) CosmosDBLikes.deleteOne(l);

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

            var query = format("SELECT VALUE l.userId FROM likes l WHERE l.shortId = '%s'", shortId);

            if (sqlOn)
                query = format("SELECT l.userId FROM \"likes\" l WHERE l.shortId = '%s'", shortId);

            Result<List<String>> data = (Result<List<String>>) this.tryQuery(query, "likes",
                    String.class);

            return errorOrValue(okUser(shrt.getOwnerId(), password), data);

        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<List<String>> getFeed(String userId, String password) {
        Log.info(() -> format("getFeed : userId = %s, pwd = %s\n", userId, password));

        List<String> l = new ArrayList<>();

        var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);

        if (sqlOn)
            query1 = format("SELECT * FROM \"short\" s WHERE s.ownerId = '%s'", userId);

        Result<List<Short>> data = (Result<List<Short>>) this.tryQuery(query1, "shorts", Short.class);

        for (Short shrt : data.value())
            l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());

        var query2 = format("SELECT VALUE f.followee FROM followers f WHERE  f.follower = '%s'", userId);

        if (sqlOn)
            query2 = format("SELECT f.followee FROM \"followers\" f WHERE  f.follower = '%s'", userId);

        Result<List<String>> data2 = (Result<List<String>>) this.tryQuery(query2, "followers",
                String.class);

        Log.warning("A entrar nos shorts dos meus seguidores");
        for (String s : data2.value()) {
            Log.warning(s);

            var query3 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s' ORDER BY s.timestamp DESC",
                    s);

            if (sqlOn)
                query3 = format("SELECT * FROM \"short\" s WHERE s.ownerId = '%s' ORDER BY s.timestamp DESC",
                        s);

            Result<List<Short>> data3 = (Result<List<Short>>) this.tryQuery(query3, "shorts",
                    Short.class);

            for (Short shrt : data3.value())
                l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());

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

        // delete shorts
        Log.warning("Está a tentar apagar os shorts");
        var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);

        if (sqlOn)
            query1 = format("SELECT * FROM \"short\" s WHERE s.ownerId = '%s'", userId);

        Result<List<Short>> data = (Result<List<Short>>) this.tryQuery(query1, "shorts",
                Short.class);

        for (Short s : data.value()) {
            Result<Short> shrt;
            if (sqlOn)
                shrt = (Result<Short>) DB.deleteOne(s);
            else
                shrt = (Result<Short>) CosmosDBShorts.deleteOne(s);
            if (shrt.isOK() && cacheOn)
                this.delInCache(s.getShortId());
            Log.warning("Apagou 1 short");
        }

        // delete follows
        Log.warning("Está a tentar apagar os follows");
        var query2 = format("SELECT * FROM followers f WHERE f.follower = '%s' OR f.followee = '%s'", userId,
                userId);

        if (sqlOn)
            query2 = format("SELECT * FROM \"followers\" f WHERE f.follower = '%s' OR f.followee = '%s'", userId,
                    userId);

        Result<List<Following>> data2 = (Result<List<Following>>) this.tryQuery(query2, "followers",
                Following.class);

        for (Following f : data2.value()) {
            Result<Following> fol;
            if (sqlOn)
                fol = (Result<Following>) DB.deleteOne(f);
            else
                fol = (Result<Following>) CosmosDBFollowers.deleteOne(f);
            if (fol.isOK() && cacheOn)
                this.delInCache(f.getFollower() + ":" + f.getFollowee());
            Log.warning("Apagou 1 Follow");
        }

        // delete likes
        Log.warning("Está a tentar apagar os likes");

        var query3 = format("SELECT * FROM likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);

        if (sqlOn)
            query3 = format("SELECT * FROM \"likes\" l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);

        Result<List<Likes>> data3 = (Result<List<Likes>>) this.tryQuery(query3, "likes", Likes.class);

        for (Likes l : data3.value()) {
            Result<Likes> lik = (Result<Likes>) CosmosDBLikes.deleteOne(l);
            if (sqlOn)
                lik = (Result<Likes>) DB.deleteOne(l);
            else
                lik = (Result<Likes>) CosmosDBLikes.deleteOne(l);
            if (lik.isOK() && cacheOn)
                this.delInCache(l.getUserId() + "_" + l.getShortId());
            Log.warning("Apagou 1 Like");
        }

        return ok();

    }

    protected Result<User> okUser(String userId, String pwd) {
        return JavaUsers.getInstance().getUser(userId, pwd);
    }

    /*
     *
     * this method was develop by chatGPT
     * 
     * It serializes an object/set of objects to put in cache
     */
    private <T> byte[] serialize(T obj) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsBytes(obj); // Serializa o objeto como JSON em byte[]
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /*
     *
     * this method was mostly develop by chatGPT
     * 
     * It desirerializes a set of data to a list of objects of the Class clazz
     */
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

    /*
     * this method was partially develop by chatGPT
     * 
     * It transforms a String to a Short when we try to retrieve the object from
     * cache
     */
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

    /*
     * puts an object in cache
     */
    private Result<Void> putInCache(String id, String obj) {
        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            jedis.set(id, obj);
            Log.info("Adicionou objeto à cache");
            return ok();

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    /*
     * deletes an object in cache
     */
    private Result<Void> delInCache(String id) {

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            jedis.del(id);
            Log.info("Apagou objeto da cache");
            return ok();

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }
    }

    /*
     * executes a query in cache and/or in the data bases, depending on the DB
     * choosen and cache's presence
     */
    private <T> Result<?> tryQuery(String query, String containerName, Class<T> clazz) {

        Result<List<T>> data;

        if (cacheOn) {
            Log.info("Cache está ativa");
            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                byte[] dataOnCache = jedis.get(String.valueOf(query.hashCode()).getBytes());

                if (dataOnCache == null) {
                    if (sqlOn)
                        data = Result.ok(DB.sql(query, clazz));
                    else
                        data = CosmosDB.getInstance(containerName).query(query, clazz);

                    if (data.isOK()) {
                        Log.info("Foi buscar os objetos à DB e colocou na cache");
                        jedis.setex(String.valueOf(query.hashCode()).getBytes(), 20, serialize(data.value()));
                    }
                } else {
                    data = Result.ok(deserializeList(dataOnCache, clazz));
                    Log.info("Obteve objeto da cache");
                }

            } catch (CosmosException e) {
                return Result.error(errorCodeFromStatus(e.getStatusCode()));
            } catch (Exception e) {
                e.printStackTrace();
                return Result.error(ErrorCode.INTERNAL_ERROR);
            }
        } else {
            Log.info("Cache não está ativa");
            if (sqlOn) {
                data = Result.ok(DB.sql(query, clazz));

            } else {

                data = CosmosDBLikes.query(query, clazz);

            }

        }

        return data;

    }

    /*
     * retrieves an object from cache
     */
    private Result<Short> getFromCache(String id, Result<List<Long>> like) {

        Result<Short> shortRes;

        try (Jedis jedis = RedisCache.getCachePool().getResource()) {

            String dataOnCache = jedis.get(id);

            if (dataOnCache != null) {
                Log.info("Obteve objeto da cache");
                shortRes = errorOrValue(parseShortFromString(dataOnCache),
                        shrt -> shrt.copyWithLikes_And_Token(like.value().get(0)));
            } else {

                if (sqlOn) {
                    shortRes = errorOrValue(DB.getOne(id, Short.class),
                            shrt -> shrt.copyWithLikes_And_Token(like.value().get(0)));
                } else
                    shortRes = errorOrValue(CosmosDBShorts.getOne(id, Short.class),
                            shrt -> shrt.copyWithLikes_And_Token(like.value().get(0)));

                if (shortRes.isOK() && cacheOn) {
                    Short item = shortRes.value();
                    Log.info("%%%%%%%%%%%%%%%%%%% foi buscar ao cosmos " + item);
                    this.putInCache(id, item.toString());
                    Log.info("&&&&&&&&&&&&&&&&&& colocou no jedis");
                }
            }

        } catch (CosmosException e) {
            return Result.error(errorCodeFromStatus(e.getStatusCode()));
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(ErrorCode.INTERNAL_ERROR);
        }

        return shortRes;

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
