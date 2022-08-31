# IMAP-Forward

## Overview

This is a small utility to forward mail from one email account to another using IMAP instead of
a forward in the email service itself.  This keeps the to and from addresses the same, and gets
around the destination service marking everything spam because it doesn't come from the original
sender.

Since the connections are made over IMAP/S this is a secure way to forward email if the original
message was inside an organization.  This is common when you have clients that want you to have
an email box on their system and send secure information in that email system, but it's not
practical to monitor all those emails.

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

## To-Do

The later versions of javax.mail have support for modern authentication, and sooner or later
someone is going to need that.
