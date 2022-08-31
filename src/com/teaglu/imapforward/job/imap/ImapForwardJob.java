/****************************************************************************
 * Copyright (c) 2022 Teaglu, LLC
 * All Rights Reserved
 ****************************************************************************/

package com.teaglu.imapforward.job.imap;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderNotFoundException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

import org.eclipse.jdt.annotation.NonNull;
import com.sun.mail.imap.IMAPFolder;
import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.imapforward.alert.AlertSink;
import com.teaglu.imapforward.alert.PrefixAlertSink;
import com.teaglu.imapforward.job.Job;

/**
 * ImapForwardJob
 *
 * A job that replicates messages from one mailbox to another
 */
public class ImapForwardJob implements Job {
	private @NonNull AlertSink alertSink;
	
	private @NonNull String name;

	// The normal amount of time we wait to cycle
	private int cycleSeconds= 20;
	
	// Fall back to slower cycle time on error so we don't spam the alert sink
	private int errorCycleSeconds= 3600;
	
	private static class Mailbox {
		private @NonNull String host;
		private @NonNull String username;
		private @NonNull String password;
		
		private Mailbox(
				@NonNull Composite spec) throws SchemaException
		{
			host= spec.getRequiredString("host");
			username= spec.getRequiredString("username");
			password= spec.getRequiredString("password");
		}
	}
	
	private class FolderPair {
		private @NonNull String source;
		private @NonNull String destination;
		
		private FolderPair(
				@NonNull Composite spec) throws SchemaException
		{
			source= spec.getRequiredString("source");
			destination= spec.getRequiredString("destination");
		}
	}
	
	private @NonNull Mailbox source;
	private @NonNull Mailbox destination;
	
	private final List<@NonNull FolderPair> pairs= new ArrayList<>();
	
	private ImapForwardJob(
			@NonNull Composite spec,
			@NonNull AlertSink alertSink) throws SchemaException
	{
		this.name= spec.getRequiredString("name");
		this.alertSink= PrefixAlertSink.Create(alertSink, "[" + name + "] ");
		
		Integer cycleSpec= spec.getOptionalInteger("seconds");
		if (cycleSpec != null) {
			cycleSeconds= cycleSpec;
		}
		
		source= new Mailbox(spec.getRequiredObject("source"));
		destination= new Mailbox(spec.getRequiredObject("destination"));
		
		Iterable<@NonNull Composite> pairSpecs= spec.getRequiredObjectArray("folders");
		for (Composite pairSpec : pairSpecs) {
			pairs.add(new FolderPair(pairSpec));
		}
	}
	
	public static @NonNull Job Create(
			@NonNull Composite spec,
			@NonNull AlertSink alertSink) throws SchemaException
	{
		return new ImapForwardJob(spec, alertSink);
	}
	
	private @NonNull IMAPFolder openFolder(
			@NonNull Store store,
			@NonNull String name) throws MessagingException
	{
		try {
			String[] parts= name.split("\\.");
			Folder folder= store.getFolder(parts[0]);
			
			for (int partNo= 1; partNo < parts.length; partNo++) {
				folder= folder.getFolder(parts[partNo]);
			}
			
			if (!(folder instanceof IMAPFolder)) {
				throw new RuntimeException("Folder is not implemented as IMAPFolder");
			}
			
			folder.open(Folder.READ_WRITE);
			
			return (IMAPFolder)folder;
		} catch (FolderNotFoundException e) {
			System.err.println(
					"Unable to open the folder " + name + " - be sure path " +
					"components are separated by dots.  Most folders are " +
					"technically under Inbox, so folder Processed might be " +
					"\"Inbox.Processed\".");
			
			throw e;
		}
	}
	
	// These are different methods to move the message.  MOVEMESSAGE was supposed to be
	// preferable because it keeps message IDs, but it blows up with a "can't move between
	// different stores" kind of message.  COPYMESSAGE seems to work so that's what I'm using
	// now -  I haven't looked to see if the message ID changes.
	private enum Method {
		MOVEMESSAGE,
		COPYMESSAGE,
		ADDMESSAGE
	}
	private Method method= Method.COPYMESSAGE;

	private volatile boolean run;
	private Lock runLock= new ReentrantLock();
	private Condition runWake= runLock.newCondition();
	
	private void runLoop() {
		Properties props= System.getProperties();
		props.setProperty("mail.store.protocol", "imaps");
		
		Session sourceSession= Session.getInstance(props);
		Session destinationSession= Session.getInstance(props);
		
		Store sourceStore= null;
		Store destinationStore= null;
		
		for (boolean localRun= true; localRun; ) {
			boolean closeStores= false;
			
			// Start with cycle seconds - any error will kick us to the error value which is
			// much longer to prevent spamming the alert sink.  Getting the same fail message
			// every 10 seconds sucks.
			int waitSeconds= cycleSeconds;
			
			try {
				// Connect the stores if they aren't already connected
				if (sourceStore == null) {
					sourceStore= sourceSession.getStore("imaps");
					sourceStore.connect(source.host, source.username, source.password);
				}
				if (destinationStore == null) {
					destinationStore= destinationSession.getStore("imaps");
					destinationStore.connect(destination.host, destination.username, destination.password);
				}

				for (FolderPair pair : pairs) {
					IMAPFolder sourceFolder= null;
					IMAPFolder destinationFolder= null;

					try {
						sourceFolder= openFolder(sourceStore, pair.source);
						destinationFolder= openFolder(destinationStore, pair.destination);
						
						Message messages[]= sourceFolder.getMessages();

						// We could theoretically do this all in one go, but I think it's safer
						// to go one at a time so we're in a consistent state on failure
						for (Message message : messages) {
							if (message != null) {
								// This just builds up something to put in the logs
								StringBuilder description= new StringBuilder();
								try {
									Address[] fromAddresses= message.getFrom();
									if (fromAddresses.length > 0) {
										description.append(fromAddresses[0].toString());
									} else {
										description.append("[?]");
									}
									
									String subject= message.getSubject();
									if (subject != null) {
										description.append(" ");
										description.append(subject);
									}
								} catch (MessagingException e) {
									alertSink.sendAlert("Error building description", e);
									description= new StringBuilder("EXCEPTION");
								}
								
								Message[] single= new Message[] { message };
								
								try {
									switch (method) {
									case MOVEMESSAGE:
										sourceFolder.moveMessages(single, destinationFolder);
										break;
										
									case COPYMESSAGE:
										sourceFolder.copyMessages(single, destinationFolder);
										message.setFlag(Flags.Flag.DELETED, true);
										break;
										
									case ADDMESSAGE:
										destinationFolder.addMessages(single);
										message.setFlag(Flags.Flag.DELETED, true);
										break;
									}
								} catch (MessagingException e) {
									alertSink.sendAlert(
											"Error Syncing Message " +
											description.toString(), e);
									
									throw e;
								}
							}
						}
					} finally {
						if (destinationFolder != null) {
							try {
								destinationFolder.close(false);
							} catch (MessagingException e) {
								alertSink.sendAlert("Error Closing Destination Folder", e);
							}
						}
						if (sourceFolder != null) {
							try {
								// The true on sourceFolder.close tells the backend to expunge
								// the folder - this is what actually does the delete.  Sometimes
								// the messages still go into a deleted folder, depending on
								// the IMAP backend.
								sourceFolder.close(true);
							} catch (MessagingException e) {
								alertSink.sendAlert("Error Closing Source Folder", e);
							}
						}
					}
				}
			} catch (Exception e) {
				alertSink.sendAlert("Error in Operations", e);

				// If something went wrong, close all the stores and retry.  Hopefully that will
				// clear out any bogus connection state.
				closeStores= true;
				
				// Wait longer so we don't spam the alert sink
				waitSeconds= errorCycleSeconds;
			}

			// This just waits for the timeout or a wake-up signal
			runLock.lock();
			try {
				if ((localRun= run)) {
					try {
						runWake.await(waitSeconds, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
					}
					localRun= run;
				}
			} finally {
				runLock.unlock();
			}

			// If we're suppsed to close the stores, or if we're running down
			if (closeStores || !localRun) {
				if (sourceStore != null) {
					try {
						sourceStore.close();
					} catch (MessagingException e) {
						alertSink.sendAlert("Error Closing Source Store", e);
					}
					sourceStore= null;
				}
	
				if (destinationStore != null) {
					try {
						destinationStore.close();
					} catch (MessagingException e) {
						alertSink.sendAlert("Error Closing Destination Store", e);
					}
					destinationStore= null;
				}
			}
		}
	}
	
	private Thread thread= null;
	
	@Override
	public void start() {
		runLock.lock();
		try {
			if (thread != null) {
				throw new RuntimeException("Duplicate thread launch");
			}
			
			run= true;
			thread= new Thread(()->{ runLoop(); },
					"job-" + name.toLowerCase().replace(' ', '-'));
			thread.start();
		} finally {
			runLock.unlock();
		}
	}
	
	@Override
	public void stop() {
		Thread waitThread= null;
		
		runLock.lock();
		try {
			if (thread == null) {
				throw new RuntimeException("Attempt to stop stopped thread");
			}
			
			run= false;
			runWake.signal();
			waitThread= thread;
			thread= null;
		} finally {
			runLock.unlock();
		}

		try {
			waitThread.join(60_000);
		} catch (InterruptedException e) {
		}
	}
}
