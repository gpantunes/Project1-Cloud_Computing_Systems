package tukano.impl;

import static java.lang.String.format;
import static tukano.api.Result.error;
import static tukano.api.Result.ErrorCode.FORBIDDEN;

import java.util.logging.Logger;
import java.util.*;

import tukano.api.Blobs;
import tukano.api.Shorts;
import tukano.api.Result;
import tukano.impl.rest.TukanoRestServer;
import tukano.impl.storage.AzureBlobStorage;
import tukano.impl.storage.AzureFilesystemStorage;
import utils.Hash;
import utils.Hex;

public class JavaBlobs implements Blobs {

	private static Blobs instance;
	private static Logger Log = Logger.getLogger(JavaBlobs.class.getName());

	public String baseURI;
	private AzureBlobStorage storage;

	synchronized public static Blobs getInstance() {
		if (instance == null)
			instance = new JavaBlobs();
		return instance;
	}

	private JavaBlobs() {
		storage = new AzureFilesystemStorage();
		baseURI = String.format("%s/%s/", TukanoRestServer.serverURI, Blobs.NAME);
	}

	@Override
	public Result<Void> upload(String blobId, byte[] bytes, String token) {
		Log.info(() -> format("upload : blobId = %s, sha256 = %s, token = %s\n", blobId, Hex.of(Hash.sha256(bytes)),
				token));


		if (!validBlobId(blobId, token))
			return error(FORBIDDEN);

		return storage.upload(toPath(blobId), bytes);

	}

	@Override
	public Result<byte[]> download(String blobId, String token) {
		Log.info(() -> format("download : blobId = %s, token=%s\n", blobId, token));

		if (!validBlobId(blobId, token))
			return error(FORBIDDEN);

		return storage.download(toPath(blobId));

	}

	@Override
	public Result<Void> delete(String blobId, String token) {
		Log.info(() -> format("delete : blobId = %s, token=%s\n", blobId, token));

		if (!validBlobId(blobId, token))
			return error(FORBIDDEN);

		return storage.delete(toPath(blobId));
	}

	@Override
	public Result<Void> deleteAllBlobs(String userId, String token) {
		Log.info(() -> format("deleteAllBlobs : userId = %s, token=%s\n", userId, token));

		if (!validBlobId(userId, token))
			return error(FORBIDDEN);

		List<String> shortList = JavaShorts.getInstance().getShorts(userId).value();

		for (String shrt : shortList) {
			Log.info("Está a apagar o blob: " + shrt);
			String shortId = shrt.substring(shrt.indexOf("ShortId: ") + 9, shrt.indexOf(" TotalLikes:"));
			this.delete(shortId, token);
		}

		return storage.delete(toPath(userId));
	}

	private boolean validBlobId(String blobId, String token) {
		Log.info("############ token: " + token);
		return Token.isValid(token, blobId);

	}

	private String toPath(String blobId) {
		return blobId.replace("+", "/");
	}

}

