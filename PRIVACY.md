# Privacy notice

MovieTorrentSearchTV stores the selected language, local viewing history, TorrServer address and a salted PBKDF2 verifier for local access control on the device. The original credential is not stored and no application data is included in Android backups.

The app contacts external catalogue, artwork, torrent-index and update services. Those services receive ordinary network metadata such as IP address, request time and user agent, and may apply their own privacy policies. A user-configured TorrServer may be contacted over the local network. At startup the app probes only the loopback address for an on-device TorrServer; the Direct distribution additionally performs a bounded scan of local subnets (port 8090) until a server is configured, and discovery can also be started or repeated manually.

The app does not provide a remote account system and does not intentionally send the local credential or local viewing history to a developer-operated server. External players, torrent clients, websites and package installers are separate applications governed by their own policies.

Operators distributing a modified build must update this notice with their legal identity, contact information, exact endpoints, analytics/crash-reporting behavior and jurisdiction-specific disclosures.
