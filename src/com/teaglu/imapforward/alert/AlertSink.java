package com.teaglu.imapforward.alert;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

/**
 * AlertSink
 *
 * A destination for alerts / errors / situations
 */
public interface AlertSink {
	/**
	 * sendAlert
	 * 
	 * Send and alert
	 * 
	 * @param message					Message to send
	 * @param exception					Exception that caused it
	 */
	public void sendAlert(
			@NonNull String message,
			@Nullable Exception exception);
}
