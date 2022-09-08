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

import org.eclipse.jdt.annotation.NonNull;

/**
 * TimeoutManager
 * 
 * A timeout manager is something that provides timeout services, that is the ability to
 * schedule a callback at some point X in the future, and then cancel it.
 * 
 * Timeouts may and usually do occur on a thread different from the scheduling thread.
 * 
 * Timeouts should not occur before the requested time, but may occur after the scheduled time
 * due to a limited thread pool or other pragmatic reasons.   This isn't a real-time scheduler.
 */
public interface TimeoutManager {
	/**
	 * schedule
	 * 
	 * Schedule a callback for a time in the future.
	 *
	 * @param when						Timestamp when callback should occur
	 * @param target					Callback to occur
	 * 
	 * @return							Callback handle
	 */
	public @NonNull Timeout schedule(
			long when,
			@NonNull TimeoutAction target);

	/**
	 * start
	 * 
	 * Start any resources / threads required by the timeout manager.
	 */
	public void start();
	
	/**
	 * stop
	 * 
	 * Stop any resources / threads used by the timeout manager.  Timeouts may still be
	 * called after this call if they were already in progress prior to the stop call.
	 */
	public void stop();
}
