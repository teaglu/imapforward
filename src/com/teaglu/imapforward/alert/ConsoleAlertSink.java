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

package com.teaglu.imapforward.alert;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ConsoleAlertSink
 * 
 * A simple alert sink that just sends everything to the console
 */
public class ConsoleAlertSink implements AlertSink {
	private static final Logger log= LoggerFactory.getLogger(ConsoleAlertSink.class);

	private ConsoleAlertSink() {}
	
	public static @NonNull AlertSink Create() {
		return new ConsoleAlertSink();
	}
	
	@Override
	public void sendAlert(
			@NonNull String message,
			@Nullable Exception exception)
	{
		log.error(message, exception);
	}
}
