# IMAP-Forward

## Overview

This is a small utility to forward mail from one email account to another using IMAP instead of
a forward in the email service itself.  This keeps the to and from addresses the same, and gets
around the destination service marking everything spam because it doesn't come from the original
sender.  Forwarding emails this way is logically equivalent to adding both accounts to 
Thunderbird and dragging the emails from one account to the other as soon as they come in.

To perform the forwarding this utility has to run as a service, since it continuously checks the
email on the source system looking for new emails.  To the email services it appears to be a
normal mail program, although the headers might make it obvious that it isn't.

Since the emails retain the source address, when you respond to the emails the reply will come
from your primary account not the account it originally came in for.  This is normally what you
want so that people start using your main email address.

## Alerts

If the program has any problems it will send them to standard output.  If you want to be notified
instead, you can specify SMTP information and the alert will be sent as an email.  This is set
up using the "alert" section in the configuration file.

## Configuration

Configuration is done via a JSON file.  By default the file is read from config.json in the
current directory.  If you set the environment variable IMAPFORWARD_CONFIG, the file will be
read from that location instead.

The following example shows all the options.

    {
        "alert": {
            "type": "smtp",
            "host": "smtp.contoso.com",
            "username": "someuser",
            "password": "hunter2",
            "from": "IMAP Forwarder <noreply@contoso.com>",
            "to": [
                "Some User <someuser@contoso.com>"
            ]
        },
        "jobs": [
            {
                "name": "forward1",
                "type": "imap-forward",
                "seconds": 10,
                "source": {
                    "host": "mail.client.com",
                    "username": "someuser",
                    "password": "hunter3"
                },
                "destination": {
                    "host": "mail.privateemail.com",
                    "username": "someuser@contoso.com",
                    "password": "hunter4"
                },
                "folders": [
                    {
                        "source": "INBOX",
                        "destination": "INBOX"
                    }
                ]
            }
        ]
    }

## Running the Program

If you compile this with "mvn package" it will create an executable JAR file under the target
directory named "com.teaglu.imapforward-$VERSION-jar-with-dependencies.jar".  You can use execute
this at a command line with the command "java -jar $FILENAME.jar".  For a permanent solution you
would normally set it up as a Linux service or build it into a docker container.

This program is compiled against Java 11 so it should work with any JDK version 11 or above.  On
Linux the openjdk11-jre package should work fine.

## To-Do

The later versions of javax.mail have support for "modern authentication", and sooner or later
someone is going to need that.
