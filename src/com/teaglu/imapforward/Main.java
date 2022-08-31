/****************************************************************************
 * Copyright (c) 2022 Teaglu, LLC
 * All Rights Reserved
 ****************************************************************************/

package com.teaglu.imapforward;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.json.JsonComposite;
import com.teaglu.imapforward.alert.AlertSink;
import com.teaglu.imapforward.alert.AlertSinkFactory;
import com.teaglu.imapforward.alert.ConsoleAlertSink;
import com.teaglu.imapforward.job.Job;
import com.teaglu.imapforward.job.JobFactory;

/**
 * Main
 * 
 * Main class for imap-forward utility.
 *
 */
public class Main {
	public static void main(String[] args) {
		List<@NonNull Job> jobList= new ArrayList<>();
		
		{
			// If environment variable is set use that - mainly used for inside a container
			String configPath= System.getenv("IMAPFORWARD_CONFIG");
			if (configPath == null) {
				configPath= "config.json";
			}
			
			File configFile= new File(configPath);
			try (InputStream inputStream= new FileInputStream(configFile)) {
				try (InputStreamReader reader= new InputStreamReader(inputStream)) {
					Composite config= JsonComposite.Parse(reader);
					
					AlertSink alertSink= null;
					
					// Build a custom alert sink if requested
					Composite alertConfig= config.getOptionalObject("alert");
					if (alertConfig != null) {
						alertSink= AlertSinkFactory.Create(alertConfig);
					} else {
						// Otherwise just use the console
						alertSink= ConsoleAlertSink.Create();
					}
					
					// Build the jobs
					Iterable<@NonNull Composite> jobSpecs= config.getRequiredObjectArray("jobs");
					for (Composite jobSpec : jobSpecs) {
						jobList.add(JobFactory.Create(jobSpec, alertSink));
					}
				}
			} catch (SchemaException se) {
				System.err.println("Error parsing configuration file");
				se.printStackTrace(System.err);
				
				System.exit(1);
			} catch (IOException ie) {
				System.err.println("Error reading configuration file");
				ie.printStackTrace(System.err);
				
				System.exit(1);
			}
		}

		// Latch to signal when shutdown hook is called
		CountDownLatch stopLatch= new CountDownLatch(1);
		
		// Set control-C / SIGTERM hook - this works maybe sometimes
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			stopLatch.countDown();
		}));

		// Start all the jobs
		for (Job job : jobList) {
			job.start();
		}

		// Wait for the shutdown hook to run
		try {
			stopLatch.await();
		} catch (InterruptedException e) {
		}

		// Stop everything gracefully
		for (Job job : jobList) {
			job.stop();
		}
	}
}
