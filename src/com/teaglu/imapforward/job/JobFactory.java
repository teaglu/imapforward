package com.teaglu.imapforward.job;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.exception.UndefinedOptionException;
import com.teaglu.imapforward.alert.AlertSink;
import com.teaglu.imapforward.job.imap.ImapForwardJob;

/**
 * JobFactory
 *
 * Static factory to build jobs
 */
public final class JobFactory {
	public static @NonNull Job Create(
			@NonNull Composite spec,
			@NonNull AlertSink alertSink) throws SchemaException
	{
		String type= spec.getRequiredString("type");
		
		switch (type) {
		case "imap-forward":
			return ImapForwardJob.Create(spec, alertSink);
			
		default:
			throw new UndefinedOptionException("Unknown job type " + type);
		}
	}
}
