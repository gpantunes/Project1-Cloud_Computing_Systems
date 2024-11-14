package tukano.auth.cookies;

import java.util.Map;

import jakarta.ws.rs.core.Cookie;

public class RequestCookies {

    private static final ThreadLocal<Map<String, Cookie>> requestCookiesThreadLocal = new ThreadLocal<>();

    public static void set(Map<String, Cookie> cookies) {
    	requestCookiesThreadLocal.set(cookies);
    }

    public static  Map<String, Cookie> get() {
        return requestCookiesThreadLocal.get();
    }

    public static void clear() {
    	requestCookiesThreadLocal.remove();
    }
}
