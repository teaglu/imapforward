package com.teaglu.imapforward.timeout;

/**
 * Callback
 * 
 * A callback is a handle returned from a CallbackManager to indicate a specific
 * scheduled callback.
 */
public interface Timeout {
	public void cancel();
}
