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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.config.ConfigManager;
import com.teaglu.configure.config.ConfigManagerFactory;
import com.teaglu.configure.config.ConfigTarget;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.configure.secret.SecretProviderFactory;
import com.teaglu.configure.secret.SecretReplacer;
import com.teaglu.configure.secret.replacer.AtIdSecretReplacer;
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
	private static final Logger log= LoggerFactory.getLogger(Main.class);
    private static final CountDownLatch quitLatch= new CountDownLatch(1);
    
	private static final List<@NonNull Job> jobList= new ArrayList<>();
	private static AlertSink alertSink= null;
	
	private static final @NonNull TimeoutManager timeoutManager= TimeoutManagerImpl.Create();
	
	public static void main(String[] args) {		
		log.info("IMAPForward Version " + getVersion() + " Starting");

		
		try {
            // Create a secret provider based on environment
            SecretProvider secretProvider= SecretProviderFactory
                    .getInstance()
                    .createFromEnvironment();

            // Create a target for the configuration manager to operate on
            ConfigTarget target= new ConfigTarget() {
                @Override
                public void apply(@NonNull Composite config) throws Exception {
                	configure(config);
                }

                @Override
                public void shutdown() {
                    quitLatch.countDown();  
                }
            };

            // Create a configuration manager based on environment
            ConfigManager configManager= null;
            
            SecretReplacer secretReplacer= AtIdSecretReplacer.Create(secretProvider);
            
            String legacyConfig= System.getenv("IMAPFORWARD_CONFIG");
            if (legacyConfig != null) {
            	configManager= ConfigManagerFactory
            			.getInstance()
            			.createFromString("file://" + legacyConfig, target, secretReplacer);
            } else {
            	configManager= ConfigManagerFactory
            			.getInstance()
            			.createFromEnvironment(target, secretReplacer);
            }
    		
    		// Start timeout manager
    		timeoutManager.start();
            
            // Start the configuration manager
            configManager.start();

            // The main thread just waits on a latch until it's time to shut everything down.
            for (boolean run= true; run;) {
                try {
                    quitLatch.await();
                    run= false;
                } catch (InterruptedException e) {
                }
            }

            // Shut down the manager normally
            configManager.stop();

    		// Start all the jobs
    		for (Job job : jobList) {
    			job.start();
    		}
            
    		// Stop the timeout manager first so timeouts are triggered
    		timeoutManager.stop();
        } catch (Exception e) {
            log.error("Error in main startup", e);
        }
		
		// Set control-C / SIGTERM hook - this works maybe sometimes
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			quitLatch.countDown();
		}));

	}
	
	private static void configure(@NonNull Composite config) throws SchemaException {
		for (Job job : jobList) {
			job.stop();
		}
		jobList.clear();
		
		// Build a custom alert sink if requested
		Composite alertConfig= config.getOptionalObject("alert");
		if (alertConfig != null) {
			alertSink= AlertSinkFactory.Create(alertConfig);
		} else {
			// Otherwise just use the console
			alertSink= ConsoleAlertSink.Create();
		}
		
		AlertSink tmpAlertSink= alertSink;
		if (tmpAlertSink == null) {
			throw new RuntimeException("Alert sink unexpectedly null");
		}
		
		// Build the jobs
		Iterable<@NonNull Composite> jobSpecs= config.getRequiredObjectArray("jobs");
		for (Composite jobSpec : jobSpecs) {
			boolean enabled= jobSpec.getOptionalBoolean("enabled", true);
			if (enabled) {
				jobList.add(JobFactory.Create(jobSpec, tmpAlertSink, timeoutManager));
			}
		}
		
		for (Job job : jobList) {
			job.start();
		}
		
        log.info("Configuration successfully applied");
	}
	
    private static @NonNull String getVersion() {
    	String version= null;
    	
    	final ClassLoader classLoader= Main.class.getClassLoader();
    	final Properties properties= new Properties();
    	
    	try {
    		final InputStream stream= classLoader.getResourceAsStream("version.properties");

	    	if (stream != null) {
	    		try {
		    		properties.load(stream);
		    		version= properties.getProperty("version");
	    		} finally {
	    			stream.close();
	    		}
	    	}
    	} catch (IOException ioException) {
    		log.error(
    				"Unable to read version from properties file",
    				ioException);
    	}
    	
    	return (version != null) ? version : "UNKNOWN";
    }
}
