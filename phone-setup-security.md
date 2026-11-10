# Phone setup security

Phone setup starts off and must be enabled explicitly in Settings. Each session
lasts at most **15 minutes**. Browser traffic and recomposition do not extend its
deadline. Leaving the page, changing profiles, backgrounding the app, switching
Off or reaching the deadline closes the session.

Enabling creates a fresh five-character, case-sensitive alphanumeric access code.
The code is generated with `SecureRandom`. Supplied wrong codes are limited to ten
failed attempts per 30-second window across clients. The shorter code is intended
for an explicitly enabled, temporary local-network session; do not expose the
listener through a public router port forward.

The server uses monotonic elapsed time, checks authorization before accepting an
upload and rechecks the lease/profile before queued settings changes. Turning it
off closes accepted connections before revoking access. Old links stop working
after revocation or expiry and are replaced when enabling a new session.

The setup page uses local HTTP. Codes travel in request URLs, and the service does
not provide transport encryption. Responses disable caching and referrer forwarding.
Use a trusted local network. Exported backups can contain sign-ins and API keys;
use a passphrase for files that will leave your private device storage.

Content-changing actions that require TV approval stay pending until accepted on
the TV. Existing PIN and child-profile settings guards still apply. Turning setup
off prevents new requests; it does not undo changes already accepted.

## Verification

The JVM tests use loopback sockets and controllable clocks to check expiry,
revocation, request limits, authentication, uploads and connection cleanup.

On a TV and phone, check:

1. Opening Phone setup leaves it Off with no reachable listener.
2. Enabling creates a working link and a countdown starting at 15:00.
3. Off, expiry, navigation away, backgrounding and profile changes close access,
   including an upload already in progress.
4. Re-enabling creates a new link; the previous code cannot authorize requests.
5. Traffic does not renew the deadline, and required TV approvals cannot be bypassed.
6. Failed authentication is throttled and sensitive exports are encrypted with the
   chosen passphrase.

The collections editor on the page lists collections, folders and source labels by
opaque reference. It also sends each folder's cover image link, so the editor can
show and change it; the browser loads that image directly, without a referrer.
Add-on URLs, API keys and private source settings are never sent.
