# Floodgate shared key

All Velocity and Paper servers must use the **same** `key.pem` so Bedrock players authenticated by Geyser/Floodgate on the proxy are accepted on backends.

Generate locally (not committed to git):

```powershell
.\infra\scripts\Generate-FloodgateKey.ps1
# Replace an existing bad key (e.g. old RSA PEM):
.\infra\scripts\Generate-FloodgateKey.ps1 -Force
```

The file must be a **base64-encoded 128-bit AES key** (Floodgate 2.x format, ~24 characters).
Do not use OpenSSL/RSA `-----BEGIN ... KEY-----` PEM files — Geyser will log
`Invalid AES key length` and Bedrock clients will see "Failed to encrypt message".

The key is baked into Docker images at `/opt/skypvp/floodgate-key/key.pem` and copied into each server's `plugins/floodgate/` directory at container start.

For production, prefer mounting the key from a Kubernetes secret instead of baking it into images.
