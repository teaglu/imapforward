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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.json.JsonComposite;
import com.teaglu.imapforward.alert.AlertSink;
import com.teaglu.imapforward.alert.AlertSinkFactory;
import com.teaglu.imapforward.alert.ConsoleAlertSink;
import com.teaglu.imapforward.job.Job;
import com.teaglu.imapforward.job.JobFactory;
import com.teaglu.imapforward.timeout.TimeoutManager;
import com.teaglu.imapforward.timeout.TimeoutManagerImpl;

/**
 * Main
 * 
 * Main class for imap-forward utility.
 *
 */
public class Main {
	private static final String VERSION= "1.1.2";
	
	private static final Logger log= LoggerFactory.getLogger(Main.class);
	
	public static void main(String[] args) {		
		log.info("IMAPForward Version " + VERSION + " Starting");
		
		TimeoutManager timeoutManager= TimeoutManagerImpl.Create();
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
						boolean enabled= jobSpec.getOptionalBoolean("enabled", true);
						if (enabled) {
							jobList.add(JobFactory.Create(jobSpec, alertSink, timeoutManager));
						}
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
		
		// Start timeout manager
		timeoutManager.start();

		// Start all the jobs
		for (Job job : jobList) {
			job.start();
		}

		// Wait for the shutdown hook to run
		try {
			stopLatch.await();
		} catch (InterruptedException e) {
		}

		// Stop the timeout manager first so timeouts are triggered
		timeoutManager.stop();
		
		// Stop everything gracefully
		for (Job job : jobList) {
			job.stop();
		}
	}
}
