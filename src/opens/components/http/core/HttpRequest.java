package opens.components.http.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;

import android.net.http.AndroidHttpClient;
import android.os.Message;

import opens.components.cache.Cache;
import opens.components.cache.serializers.CacheSerializer;
import opens.components.http.ImageRequest;

/**
 * HttpResponse base class. Should be subclassed only if for some reason
 * HttpObjectRequest is not suitable for subclassing
 * Use HttpObjectRequest as a super class when building custom requests
 * 
 * @author Vatroslav Dino Matijas
 *
 */
public abstract class HttpRequest implements Runnable {

	
	public static final int METHOD_GET = 0;
	
	public static final int METHOD_POST = 1;
	
	public static final int REQUEST_STARTED = 0;
	public static final int REQUEST_FINISHED = 1;
	public static final int REQUEST_SUCCESS = 2;
	public static final int REQUEST_ERROR = 3;
	 
	public static final int DoNotWriteToCacheCachePolicy = 1;
	public static final int DoNotReadFromCacheCachePolicy = 2;
	/**
	 * If the request fails check in cache and return cached object instead
	 */
	public static final int FallbackToCacheIfLoadFailsCachePolicy = 4;
	
	public static final int DefaultCachePolicy = FallbackToCacheIfLoadFailsCachePolicy | DoNotReadFromCacheCachePolicy;
	
	private HttpRequestHandler	handler; 	
	
	private int method = METHOD_GET;
	
	private String url;
	
	private boolean error;
	
	private boolean finished;
	
	private boolean canceled;
	
	private Cache cache;
	
	private boolean responseFromCache = false;
	
	private int cachePolicy;
	
	public HttpRequest() {
		super();
		this.handler = new HttpRequestHandler();
		cachePolicy = DefaultCachePolicy;
	}
		
	public boolean isCachePolicySet(int cachePolicy) {
		return (this.cachePolicy & cachePolicy) != 0;
	}
	
	public int getCachePolicy() {
		return cachePolicy;
	}

	public void setCachePolicy(int cachePolicy) {
		this.cachePolicy = cachePolicy;
	}	
	
	public boolean isError() {
		return error;
	}

	public boolean isFinished() {
		return finished;
	}

	public boolean isCanceled() {
		return canceled;
	}

	public void setCanceled(boolean canceled) {
		this.canceled = canceled;
	}
	
	public HttpRequest get(String url) {
		this.url = url;
		return this;
	}
	
	public void onSuccess(Object target, String action) {
		handler.setOnSuccess(new HttpRequestHandler.TargetAction(target, action));
	}
	
	public void onError(Object target, String action) {
		handler.setOnError(new HttpRequestHandler.TargetAction(target, action));
	}
	
	public void onStart(Object target, String action) {
		handler.setOnStart(new HttpRequestHandler.TargetAction(target, action));
	}
	
	public void onFinish(Object target, String action) {
		handler.setOnFinish(new HttpRequestHandler.TargetAction(target, action));
	}
	
	public String getUrl() {
		return url;
	}
	
	public boolean isResponseFromCache() {
		return responseFromCache;
	}
	
	public String getRequestCacheKey() {
		return url.replaceAll("[^0-9^a-z^A-Z]", "");
	}
	
	public Cache getCache () {
		return cache;
	}
	
	public void setCache(Cache cache) {
		this.cache = cache;
	}
	
	public String getString(HttpResponse response) throws IllegalStateException, IOException {
		String line = "";
	    StringBuilder total = new StringBuilder();	    
	    BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
	    while ((line = rd.readLine()) != null) { 
	        total.append(line); 
	    }	    
	    // Return full string
	    return total.toString();
	}
	
	public JSONArray getJSONArray(HttpResponse response) throws IllegalStateException, IOException, JSONException {
		JSONTokener tokener = new JSONTokener(getString(response));
		return new JSONArray(tokener);
	}	

	/**
	 * CacheSupport
	 * Override this method in subclasses to provide custom caching support
	 * @see ImageRequest for example implementation
	 */
	abstract protected CacheSerializer getCacheSerializer();
	
	/**
	 * CacheSupport
	 * Override this method in subclasses to provide custom caching support
	 * @see ImageRequest for example implementation
	 */
	abstract protected Object getObjectToCache();
	
	/**
	 * CacheSupport
	 * Override this method in subclasses to provide custom caching support
	 * @see ImageRequest for example implementation
	 */
	abstract protected void loadFromCachedObject(Object state);
	
	/**
	 * Override this method to support custom request processing. This method is called on 
	 * the worker thread, and not on the UI thread.
	 * @param response
	 * @throws Exception
	 */
	abstract protected void onHttpResponseReceived(HttpResponse response)  throws Exception;
	
	/**
	 * Override this method to suport a custom error callback after error on a download.
	 * This method must be called on the worker thread and not on the UI thread
	 * @param message the error message from exception 
	 * @author Leonardo Rossetto <leonardoxh@gmail.com>
	 */
	protected void onErrorCallBack(String message) { }
	
	private void sendMessageToHandler(int what, Object obj, String errorMessage) {
		if (this.handler == null) {
			return;
		}
		
		Message msg = Message.obtain(handler, what, obj);
		handler.sendMessage(msg);
		
		//TODO implementeation of other callbacks
		switch(what) {
			case HttpRequest.REQUEST_STARTED: {
				break;
			}
			case HttpRequest.REQUEST_FINISHED: {
				break;
			}
			case HttpRequest.REQUEST_SUCCESS: {
				break;
			}
			case HttpRequest.REQUEST_ERROR: {
				this.onErrorCallBack(errorMessage);
				break;
			}
		}
	}
	
	private boolean tryToLoadFromCache(Cache cache, CacheSerializer cacheSerializer) {
		Object cachedState = null;
		if (cache != null && cacheSerializer != null) { //try to get memento from cache
			cachedState = cache.get(getRequestCacheKey(), cacheSerializer);			
		}
		
		if (cachedState != null) { //Get response from cache
			this.responseFromCache = true;
			this.loadFromCachedObject(cachedState);
			this.finished = true;
			sendMessageToHandler(REQUEST_SUCCESS, this, null);			
			return true;
		}
		return false;
	}
	
	public void run() {
		if (canceled) {
			return;
		}
		
		CacheSerializer cacheSerializer = getCacheSerializer();
		Cache cache = getCache();
				
		if (isCachePolicySet(DoNotReadFromCacheCachePolicy) ==false) {
			if (tryToLoadFromCache(cache, cacheSerializer)) {
				return;
			}
		}
		
		AndroidHttpClient client = AndroidHttpClient.newInstance("Catalog Brasil Thread");
		//HttpConnectionParams.setSoTimeout(client.getParams(), 25000);	//TODO - resuport parameters
		
		sendMessageToHandler(REQUEST_STARTED, this, null);
		try {
			switch(method) {
				case METHOD_GET: {
					HttpResponse response = client.execute(new HttpGet(url));
					this.onHttpResponseReceived(response);
					client.close();
					break;
				}
			}
			client.close();
			sendMessageToHandler(REQUEST_SUCCESS, this, null);
		} 
		catch (Exception e) {
			e.printStackTrace();
			finished = true;
			error = true;
			client.close();
			sendMessageToHandler(REQUEST_ERROR, this, e.getMessage());
		}
		finally {
			client.close();
		}
		sendMessageToHandler(REQUEST_FINISHED, this, null);
	}	
}
