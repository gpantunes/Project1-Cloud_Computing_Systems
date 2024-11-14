package tukano.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FakeRedisLayer {
	Map<String, Session> sessions = new ConcurrentHashMap<>();

	private static FakeRedisLayer instance;
	synchronized public static FakeRedisLayer getInstance() {
		if(instance == null )
			instance = new FakeRedisLayer();
		return instance;
	}
	
	
	public void putSession(Session s) {
		sessions.put(s.uid(), s);
	}
	
	public Session getSession(String uid) {
		return sessions.get(uid);
	}
}
