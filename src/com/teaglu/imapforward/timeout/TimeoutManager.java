package com.teaglu.imapforward.timeout;

import org.eclipse.jdt.annotation.NonNull;

/**
 * TimeoutManager
 * 
 * A timeout manager is something that provides timeout services, that is the ability to
 * schedule a callback at some point X in the future, and then cancel it.
 * 
 * Callbacks may and usually do occur on a thread different from the scheduling thread.
 * 
 * Callback should not occur before the requested time, but may occur after the scheduled time
 * due to a limited thread pool or other pragmatic reasons.
 * 
 * This isn't a real-time scheduler.
 */
public interface TimeoutManager {
	/**
	 * schedule
	 * 
	 * Schedule a callback for a time in the future.
	 *
	 * @param when						Timestamp when callback should occur
	 * @param target					Callback to occur
	 * 
	 * @return							Callback handle
	 */
	public @NonNull Timeout schedule(long when, @NonNull TimeoutAction target);

	public void start();
	public void stop();
}
