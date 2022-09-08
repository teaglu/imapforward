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

/**
 * Timeout
 * 
 * A Timeout is a handle returned from a TimeoutkManager to indicate a specific
 * scheduled timeout.  The only thing you can do with it is cancel it - normally after
 * you've completed whatever task was supposed to complete before the timeout expires.
 */
public interface Timeout {
	/**
	 * cancel
	 * 
	 * Cancel the timeout.  If the timeout has already expired this will have no effect.
	 */
	public void cancel();
}
