/****************************************************************************
 * Copyright (c) 2022 Teaglu, LLC
 * All Rights Reserved
 ****************************************************************************/

package com.teaglu.imapforward.job;

/**
 * Job
 *
 * Any continuously-running job managed by the Main class
 */
public interface Job {
	/**
	 * start
	 * 
	 * Allocate resources and start background threads
	 */
	public void start();
	
	/**
	 * stop
	 * 
	 * Stop background threads and release resources
	 */
	public void stop();
}
