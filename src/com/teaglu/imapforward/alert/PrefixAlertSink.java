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

/**
 * PrefixAlertSink
 * 
 * Alert sink that wraps another alert sink but prefixes all the messages with a string.  This
 * is a convenience wrapper to indicate which job is referenced.
 * 
 */
public class PrefixAlertSink implements AlertSink {
	private @NonNull AlertSink sink;
	private @NonNull String prefix;
	
	private PrefixAlertSink(
			@NonNull AlertSink sink,
			@NonNull String prefix)
	{
		this.sink= sink;
		this.prefix= prefix;
	}
	
	public static @NonNull AlertSink Create(
			@NonNull AlertSink sink,
			@NonNull String prefix)
	{
		return new PrefixAlertSink(sink, prefix);
	}

	@Override
	public void sendAlert(
			@NonNull String message,
			@Nullable Exception exception)
	{
		sink.sendAlert(prefix + message, exception);
	}
}
