/****************************************************************************
 * Copyright 2022 Teaglu, LLC                                               *
 *                                                                          *
 * Licensed under the Apache License, Version 2.0 (the "License");          *
 * you may not use this file except in compliance with the License.         *
 * You may obtain a copy of the License at                                  *
 *                                                                          *
 *   http://www.apache.org/licenses/LICENSE-2.0                             *
 *                                                                          *
 * Unless required by applicable law or agreed to in writing, software      *
 * distributed under the License is distributed on an "AS IS" BASIS,        *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 * See the License for the specific language governing permissions and      *
 * limitations under the License.                                           *
 ****************************************************************************/

package com.teaglu.imapforward.job.imap;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.imapforward.alert.AlertSink;
import com.teaglu.imapforward.alert.PrefixAlertSink;
import com.teaglu.imapforward.job.Job;
import com.teaglu.imapforward.timeout.Timeout;
import com.teaglu.imapforward.timeout.TimeoutManager;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

/**
 * ImapForwardJob
 *
 * A job that replicates messages from one mailbox to another
 */
public class ImapForwardJob implements Job {
	private static final Logger log= LoggerFactory.getLogger(ImapForwardJob.class);
	
	private final @NonNull AlertSink alertSink;
	
	private final @NonNull String name;
	
	private final boolean imapDebug;

	// The normal amount of time we wait to cycle
	private int cycleSeconds= 20;
	
	// Fall back to slower cycle time on error so we don't spam the alert sink
	private int errorCycleSeconds= 3600;

	private final @NonNull TimeoutManager timeoutManager;
	private long timeoutMilliseconds= 60_000;
	
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
			@NonNull AlertSink alertSink,
			@NonNull TimeoutManager timeoutManager) throws SchemaException
	{
		this.name= spec.getRequiredString("name");
		this.alertSink= PrefixAlertSink.Create(alertSink, "[" + name + "] ");
		this.timeoutManager= timeoutManager;
		
		this.imapDebug= spec.getOptionalBoolean("debug", false);
		
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
			@NonNull AlertSink alertSink,
			@NonNull TimeoutManager timeoutManager) throws SchemaException
	{
		return new ImapForwardJob(spec, alertSink, timeoutManager);
	}
	
	private @NonNull Folder openFolder(
			@NonNull Store store,
			@NonNull String name) throws MessagingException
	{
		try {
			String[] parts= name.split("\\.");
			Folder folder= store.getFolder(parts[0]);
			
			for (int partNo= 1; partNo < parts.length; partNo++) {
				folder= folder.getFolder(parts[partNo]);
			}
			
			folder.open(Folder.READ_WRITE);
			
			return folder;
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
	//
	// 20220907 - removed MOVEMESSAGE, it doesn't work between accounts.  The driver was that
	// after converting to maven I can't get access to IMAPFolder under com.sun.  I'd like to
	// keep working with IMAPFolders to have access to the the moveMessage call, so that we
	// could add an option to move messages after processing instead of deleting.  I guess I'll
	// loop back to that later, converting to maven has caused N problems.
	
	private enum Method {
		COPYMESSAGE,
		ADDMESSAGE
	}
	private Method method= Method.COPYMESSAGE;

	private volatile boolean run;
	private Lock runLock= new ReentrantLock();
	private Condition runWake= runLock.newCondition();
	
	private void runLoop() {
		log.info("Thread for job " + name + " is running");
		
		Properties props= System.getProperties();
		props.setProperty("mail.store.protocol", "imaps");
		
		if (imapDebug) {
			// This dumps out all the IMAP commands on the console if we need to track anything
			// down or see what it's doing.
			props.setProperty("mail.debug", "true");
		}
		
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
			
			// Schedule a timeout to detect thread hangs
			//
			// For some reason there's this one specific message in Lotus Notes where the IMAP
			// state gets screwed up and the fetch thread just sits there doing nothing.  99:1
			// it's an implementation bug in notes, but I'm having to hit a version 8.x server
			// that can't be updated for "reasons".
			//
			// This at least keeps us from going catatonic.
			Timeout timeout= timeoutManager.schedule(
					System.currentTimeMillis() + timeoutMilliseconds,
					() -> { timeoutFired(); });
			
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
					Folder sourceFolder= null;
					Folder destinationFolder= null;

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
									case COPYMESSAGE:
										sourceFolder.copyMessages(single, destinationFolder);
										message.setFlag(Flags.Flag.DELETED, true);
										break;
										
									case ADDMESSAGE:
										destinationFolder.appendMessages(single);
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
			} finally {
				// Cancel the timeout if it hasn't already fired
				timeout.cancel();
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
		
		log.info("Thread for job " + name + " is shut down");
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
			
			thread.interrupt();
			
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
	
	private void timeoutFired() {
		alertSink.sendAlert(
				"Detected thread hang - attempting auto-restart", null);
		
		log.warn("Attempting emergency stop of thread due to hang");
		stop();
		
		// Wait the error time before restarting the thread - this keeps from spamming
		// somebody's inbox with the same error every ten seconds.
		log.info("Thread stop was successful - waiting to retry");
		try {
			Thread.sleep(errorCycleSeconds * 1000);
		} catch (InterruptedException e) {
		}

		log.info("Attempting auto-restart of thread");
		start();
		
		alertSink.sendAlert("Auto-restart was successful", null);
	}
}
