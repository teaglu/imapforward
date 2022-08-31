/****************************************************************************
 * Copyright (c) 2022 Teaglu, LLC
 * All Rights Reserved
 ****************************************************************************/

package com.teaglu.imapforward.alert;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ConsoleAlertSink
 * 
 * A simple alert sink that just sends everything to the console
 */
public class ConsoleAlertSink implements AlertSink {
	private static final Logger log= LoggerFactory.getLogger(ConsoleAlertSink.class);

	private ConsoleAlertSink() {}
	
	public static @NonNull AlertSink Create() {
		return new ConsoleAlertSink();
	}
	
	@Override
	public void sendAlert(
			@NonNull String message,
			@Nullable Exception exception)
	{
		log.error(message, exception);
	}
}
