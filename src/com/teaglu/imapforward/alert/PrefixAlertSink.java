/****************************************************************************
 * Copyright (c) 2022 Teaglu, LLC
 * All Rights Reserved
 ****************************************************************************/

package com.teaglu.imapforward.alert;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

/**
 * PrefixAlertSink
 * 
 * Alert sink that wraps another alert sink but prefixes all the messages with a string.  This
 * is a convenience wrapper to indicate which job is referenced.
 * 
 */
public class PrefixAlertSink implements AlertSink {
	private @NonNull AlertSink sink;
	private @NonNull String prefix;
	
	private PrefixAlertSink(
			@NonNull AlertSink sink,
			@NonNull String prefix)
	{
		this.sink= sink;
		this.prefix= prefix;
	}
	
	public static @NonNull AlertSink Create(
			@NonNull AlertSink sink,
			@NonNull String prefix)
	{
		return new PrefixAlertSink(sink, prefix);
	}

	@Override
	public void sendAlert(
			@NonNull String message,
			@Nullable Exception exception)
	{
		sink.sendAlert(prefix + message, exception);
	}
}
