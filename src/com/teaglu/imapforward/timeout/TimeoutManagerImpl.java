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
 * Implementation of timeout manager using a fixed pool of threads.
 *
 */
public class TimeoutManagerImpl implements TimeoutManager {
	private Logger log= LoggerFactory.getLogger(TimeoutManagerImpl.class);
	
	private class CallbackImpl implements Timeout, Runnable {
		private final long when;
		private final @NonNull TimeoutAction target;
		
		private CallbackImpl(
				long when,
				@NonNull TimeoutAction target)
		{
			this.when= when;
			this.target= target;
		}

		@Override
		public void cancel() {
			removeCallback(this);
		}

		@Override
		public void run() {
			target.call();
		}
	}
	
	private class CallbackByWhen implements Comparator<CallbackImpl> {
		@Override
		public int compare(CallbackImpl a, CallbackImpl b) {
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
	
	private final PriorityQueue<CallbackImpl> queue=
			new PriorityQueue<>(10, new CallbackByWhen());
	
	private Lock queueLock= new ReentrantLock();
	private Condition queueWake= queueLock.newCondition();

	@Override
	public @NonNull Timeout schedule(long when, @NonNull TimeoutAction target) {
		CallbackImpl callback= new CallbackImpl(when, target);
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

	private void removeCallback(@NonNull Timeout handle) {
		queueLock.lock();
		try {
			queue.remove(handle);
		} finally {
			queueLock.unlock();
		}
	}

	private volatile boolean queueRun;
	
	private class CallbackRunner implements Runnable {
		@Override
		public void run() {
			for (boolean run= true; run; ) {
				long now= System.currentTimeMillis();
				CallbackImpl callback= null;
				
				queueLock.lock();
				try {
					while (queueRun && (callback == null)) {
						long waitUntil= 0;
						
						callback= queue.peek();
						if (callback != null) {
							if (callback.when > now) {
								waitUntil= callback.when;
								callback= null;
							} else {
								callback= queue.poll();
							}
						}
						
						if (callback == null) {
							try {
								if (waitUntil > 0) {
									queueWake.await(waitUntil - now, TimeUnit.MILLISECONDS);
								} else {
									queueWake.await();
								}
							} catch (InterruptedException e) {
							}
							
							now= System.currentTimeMillis();
						}
					}
					run= queueRun;
				} finally {
					queueLock.unlock();
				}
				
				if (run && (callback != null)) {
					try {
						callbackExecutor.execute(callback);
					} catch (Exception e) {
						log.error("Exception in callback thread", e);
					}
				}
			}
		}
	}
	
	private Thread leaderThread;
	private ExecutorService callbackExecutor;
	
	private AtomicInteger threadCounter= new AtomicInteger(1);
	
	public void start() {
		ThreadFactory threadFactory= new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "timeout-worker-" + threadCounter.getAndIncrement());
			}};
		
		callbackExecutor= Executors.newFixedThreadPool(2, threadFactory);
		
		queueRun= true;
		
		leaderThread= new Thread(
				new CallbackRunner(), "timeout-leader");
			
		leaderThread.start();
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
			leaderThread.join();
		} catch (InterruptedException e) {
		}
		
		leaderThread= null;
		callbackExecutor.shutdownNow();
	}
}
