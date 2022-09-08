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

package com.teaglu.imapforward.timeout;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TimeoutManagerImpl
 * 
 * Implementation of timeout manager using a single scheduler thread and a ExecutorService
 * for delivery of the timeouts.
 *
 */
public class TimeoutManagerImpl implements TimeoutManager {
	private static final Logger log= LoggerFactory.getLogger(TimeoutManagerImpl.class);
	
	private class TimeoutImpl implements Timeout, Runnable {
		// Absolute time when the timeout should be called
		private final long when;
		
		// The timeout callback
		private final @NonNull TimeoutAction target;
		
		private TimeoutImpl(
				long when,
				@NonNull TimeoutAction target)
		{
			this.when= when;
			this.target= target;
		}

		@Override
		public void cancel() {
			// This is why the inner class can't be static
			removeCallback(this);
		}

		@Override
		public void run() {
			// The same TimeoutImpl object serves as the Runnable passed to the
			// ExecutorService.  We try to catch any exceptions/errors so that a problem
			// in a callback doesn't disrupt the executor.
			
			try {
				target.call();
			} catch (Exception e) {
				log.error("Exception in timer callback thread", e);
			} catch (Error e) {
				log.error("Error in timer callback thread, shutting down", e);
				
				// An error is usually a null pointer or something else indicating corruption,
				// so better to shut down and let the container recycle.
				System.exit(1);
			}
		}
	}
	
	// Comparator to sort our heap by timeout time
	private class CallbackByWhen implements Comparator<TimeoutImpl> {
		@Override
		public int compare(TimeoutImpl a, TimeoutImpl b) {
			if (a.when < b.when) {
				return -1;
			} else if (a.when > b.when) {
				return 1;
			} else {
				return 0;
			}
		}
	}
	
	private TimeoutManagerImpl() {
	}
	
	public static @NonNull TimeoutManager Create()
	{
		return new TimeoutManagerImpl();
	}

	// Default size of the priority queue
	private static final int PRIOQ_DEFAULT_SIZE= 10;
	
	// This is a priority queue sorted by expiry time, so the dispatch loop only needs to
	// look at the first member to find the next time it needs to do something.
	private final PriorityQueue<TimeoutImpl> queue=
			new PriorityQueue<>(PRIOQ_DEFAULT_SIZE, new CallbackByWhen());

	// Lock structures for the queue
	private final Lock queueLock= new ReentrantLock();
	private final Condition queueWake= queueLock.newCondition();

	// Also protected by the queue lock
	private volatile boolean queueRun;
	
	@Override
	public @NonNull Timeout schedule(
			long when,
			@NonNull TimeoutAction target)
	{
		TimeoutImpl callback= new TimeoutImpl(when, target);
		queueLock.lock();
		try {
			queue.add(callback);
			
			if (callback == queue.peek()) {
				queueWake.signal();
			}
		} finally {
			queueLock.unlock();
		}
		return callback;
	}

	// This is called by the cancel call on the timeout
	private void removeCallback(@NonNull Timeout handle) {
		queueLock.lock();
		try {
			boolean isFirst= (handle == queue.peek());
			queue.remove(handle);
			
			if (isFirst) {
				// All this really does is prevent a spurious wakeup, but it seems like the
				// correct thing to do...
				queueWake.signal();
			}
		} finally {
			queueLock.unlock();
		}
	}
	
	// This is the dispatch thread
	private class Dispatcher implements Runnable {
		@Override
		public void run() {
			for (boolean run= true; run; ) {
				long now= System.currentTimeMillis();
				TimeoutImpl timeout= null;
				
				queueLock.lock();
				try {
					while (queueRun && (timeout == null)) {
						long waitUntil= 0;
						
						timeout= queue.peek();
						if (timeout != null) {
							if (timeout.when > now) {
								waitUntil= timeout.when;
								timeout= null;
							} else {
								timeout= queue.poll();
							}
						}
						
						if (timeout == null) {
							try {
								if (waitUntil > 0) {
									queueWake.await(waitUntil - now, TimeUnit.MILLISECONDS);
								} else {
									queueWake.await();
								}
							} catch (InterruptedException e) {
							}
							
							// Update our clock - we don't want more system calls than we need,
							// although VDSO makes it kind of a moot point
							now= System.currentTimeMillis();
						}
					}
					run= queueRun;
				} finally {
					queueLock.unlock();
				}
				
				// Do everything outside the lock that we can
				if (run && (timeout != null)) {
					// Schedule the timeout on the executor
					callbackExecutor.execute(timeout);
				}
			}
		}
	}
	
	private Thread dispatchThread;
	private ExecutorService callbackExecutor;
	
	private AtomicInteger threadCounter= new AtomicInteger(1);
	
	public void start() {
		// Name the threads something useful, since the names show up in SLF4J
		ThreadFactory threadFactory= new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "timeout-worker-" + threadCounter.getAndIncrement());
			}};

		// Start the executor
		callbackExecutor= Executors.newFixedThreadPool(2, threadFactory);
		
		queueRun= true;
		
		dispatchThread= new Thread(
				new Dispatcher(), "timeout-dispatch");
			
		dispatchThread.start();
	}
	
	public void stop() {
		queueLock.lock();
		try {
			queueRun= false;
			queueWake.signalAll();
		} finally {
			queueLock.unlock();
		}
		
		try {
			dispatchThread.join();
		} catch (InterruptedException e) {
		}
		
		dispatchThread= null;
		
		// I'm a little worried this could interrupt threads without executing any final blocks,
		// but I'm not sure of the exact semantics on the executors.  In any case this is during
		// shutdown.
		callbackExecutor.shutdownNow();
	}
}
