package com.teaglu.imapforward.alert;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.exception.UndefinedOptionException;

/**
 * AlertSinkFactory
 *
 * Static factory to build alert sinks
 */
public final class AlertSinkFactory {
	public static @NonNull AlertSink Create(
			@NonNull Composite spec) throws SchemaException
	{
		String type= spec.getRequiredString("type");
		switch (type) {
		case "console":
			return ConsoleAlertSink.Create();
			
		case "smtp":
			return SmtpAlertSink.Create(spec);
			
		default:
			throw new UndefinedOptionException("Alert type " + type + " not known");
		}
	}
}
