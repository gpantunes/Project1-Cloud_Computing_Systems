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

//import com.azure.cosmos.CosmosContainer;
//import com.azure.cosmos.CosmosException;
//import com.azure.cosmos.models.CosmosItemRequestOptions;
//import com.azure.cosmos.models.CosmosQueryRequestOptions;
//import com.azure.cosmos.models.PartitionKey;

import tukano.api.Blobs;
import tukano.api.Result;
import tukano.api.Short;
import tukano.api.Shorts;
import tukano.api.User;
import tukano.impl.data.Following;
import tukano.impl.data.Likes;
import tukano.impl.rest.TukanoRestServer;
import utils.CosmosDBFollowers;
import utils.CosmosDBLikes;
import utils.CosmosDBShorts;

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
            var blobUrl = format("%s/%s/%s", TukanoRestServer.serverURI, Blobs.NAME, shortId);
            var shrt = new Short(shortId, userId, blobUrl);

            return errorOrValue(CosmosDBShorts.getInstance().insertOne(shrt),
                    s -> s.copyWithLikes_And_Token(0));
        });
    }

    @Override
    public Result<Short> getShort(String shortId) {
        Log.info(() -> format("getShort : shortId = %s\n", shortId));

        if (shortId == null) {
            return error(BAD_REQUEST);
        }

        var query = format("SELECT VALUE COUNT(l.shortId) FROM shorts l WHERE l.shortId = '%s'", shortId);
        var like = CosmosDBShorts.getInstance().query(query, Long.class);
        var likes = like.value();
        return errorOrValue(CosmosDBShorts.getInstance().getOne(shortId, Short.class),
                shrt -> shrt.copyWithLikes_And_Token(likes.get(0)));
    }

    @Override
    public Result<Void> deleteShort(String shortId, String password) {
        Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

        return errorOrResult(getShort(shortId), shrt -> {

            return errorOrResult(okUser(shrt.getOwnerId(), password), user -> {

                // Delete associated likes
                var query1 = format("SELECT * FROM shorts l WHERE l.shortId = '%s'", shortId);
                var result1 = CosmosDBShorts.getInstance().query(query1, Likes.class).value();

                for (Likes l : result1)
                    CosmosDBShorts.getInstance().deleteOne(l);

                // Delete the short
                CosmosDBShorts.getInstance().deleteOne(shrt);

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

        var query = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);
        var result = CosmosDBShorts.getInstance().query(query, Short.class).value();

        List<String> l = new ArrayList<>();

        for(Short s: result)
            l.add("ShortId: " + s.getShortId() + " TotalLikes: " + s.getTotalLikes());

        return errorOrValue(okUser(userId), l);
    }

    @Override
    public Result<Void> follow(String userId1, String userId2, boolean isFollowing, String password) {
        Log.info(() -> format("follow : userId1 = %s, userId2 = %s, isFollowing = %s, pwd = %s\n", userId1, userId2,
                isFollowing, password));

        return errorOrResult(okUser(userId1, password), user -> {
            var f = new Following(userId1, userId2);
            Log.info("Cria o objeto follow");
            return errorOrVoid(okUser(userId2),
                    isFollowing ? CosmosDBFollowers.getInstance().insertOne(f)
                            : CosmosDBFollowers.getInstance().deleteOne(f));
        });
    }

    @Override
    public Result<List<String>> followers(String userId, String password) {
        Log.info(() -> format("followers : userId = %s, pwd = %s\n", userId, password));

        var query = format("SELECT VALUE f.follower FROM followers f WHERE f.followee = '%s'", userId);
        return errorOrValue(okUser(userId, password),
                CosmosDBFollowers.getInstance().query(query, String.class).value());
    }

    @Override
    public Result<Void> like(String shortId, String userId, boolean isLiked, String password) {
        Log.info(() -> format("like : shortId = %s, userId = %s, isLiked = %s, pwd = %s\n", shortId, userId, isLiked,
                password));

        return errorOrResult(getShort(shortId), shrt -> {
            var l = new Likes(userId, shortId, shrt.getOwnerId());
            Log.info("Objeto Like criado");
            return errorOrVoid(okUser(userId, password),
                    isLiked ? CosmosDBLikes.getInstance().insertOne(l)
                            : CosmosDBLikes.getInstance().deleteOne(l));
        });
    }

    @Override
    public Result<List<String>> likes(String shortId, String password) {
        Log.info(() -> format("likes : shortId = %s, pwd = %s\n", shortId, password));

        return errorOrResult(getShort(shortId), shrt -> {

            var query = format("SELECT VALUE l.userId FROM likes l WHERE l.shortId = '%s'", shortId);

            return errorOrValue(okUser(shrt.getOwnerId(), password),
                    CosmosDBLikes.getInstance().query(query, String.class).value());
        });
    }

    @Override
    public Result<List<String>> getFeed(String userId, String password) {
        Log.info(() -> format("getFeed : userId = %s, pwd = %s\n", userId, password));

        var query1 = format("SELECT VALUE [s.id, s.timestamp] FROM shorts s WHERE s.ownerId = '%s'", userId);
        var result1 = CosmosDBShorts.getInstance().query(query1, Short.class).value();

        var query2 = format("SELECT VALUE f.followee FROM followers f WHERE  f.follower = '%s'", userId);
        var result2 = CosmosDBFollowers.getInstance().query(query2, String.class).value();

        List<String> l = new ArrayList<>();

        for (Short shrt : result1)
            l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());

        Log.warning("A entrar nos shorts dos meus seguidores");
        for (String s : result2) {
            Log.warning(s);
            var query3 = format(
                    "SELECT * FROM shorts s WHERE s.ownerId = '%s' ORDER BY s.timestamp DESC",
                    s);
            var result3 = CosmosDBShorts.getInstance().query(query3, Short.class).value();
            for (Short shrt : result3)
                l.add("ShortId: " + shrt.getShortId() + " TimeStamp: " + shrt.getTimestamp());
        }

        return errorOrValue(okUser(userId, password), l);
    }

    protected Result<User> okUser(String userId, String pwd) {
        return JavaUsers.getInstance().getUser(userId, pwd);
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

    @Override
    public Result<Void> deleteAllShorts(String userId, String password, String token) {
        Log.info(() -> format("deleteAllShorts : userId = %s, password = %s, token = %s\n", userId, password, token));

        /*
         * if (!Token.isValid(token, userId)) {
         * return error(FORBIDDEN);
         * }
         */

        Log.warning("Está a tentar apagar os shorts");

        // delete shorts
        var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);
        var result1 = CosmosDBShorts.getInstance().query(query1, Short.class).value();

        for (Short s : result1) {
            CosmosDBShorts.getInstance().deleteOne(s);
            Log.warning("Apagou 1 short");
        }

        // delete follows
        var query2 = format("SELECT * FROM followers f WHERE f.follower = '%s' OR f.followee = '%s'", userId, userId);
        var result2 = CosmosDBFollowers.getInstance().query(query2, Following.class).value();

        for (Following f : result2)
            CosmosDBFollowers.getInstance().deleteOne(f);

        // delete likes
        var query3 = format("SELECT * FROM likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);
        var result3 = CosmosDBLikes.getInstance().query(query3, Likes.class).value();

        for (Likes l : result3)
            CosmosDBLikes.getInstance().deleteOne(l);

        return ok();

    }

}
