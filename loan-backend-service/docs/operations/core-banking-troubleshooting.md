# Core banking integration troubleshooting

The HTTP provider calls only the configured base URL and a relative path per operation. An absolute path, a metadata address, or a URL with embedded credentials is rejected before a socket is opened. Insecure TLS is rejected at startup.

## Timeout or connection failure

The instruction moves to `UNKNOWN` when the outcome is not known. Enquiry is the next step. The submit call is not repeated while the outcome is unknown. Authentication failures are not retried. The circuit opens after the configured failure threshold and then enquiry returns service unavailable until the open interval passes.

## Webhook rejected

The webhook requires the shared HMAC secret, a timestamp inside the replay window, and a message id that matches the body. A repeated message id is stored once and does not post again. A stale timestamp is rejected. An empty secret makes the endpoint return service unavailable rather than accepting unsigned events.

## Provider not configured

`CORE_BANKING_PROVIDER=http` without a base URL, authentication method, or path fails startup in production. The mock provider is available for local and test only. Credit bureau, BVN, and NIN adapters do not call a vendor until a contract is configured. Their current adapters return a local result and do not open a network connection.

## What to collect

- Transaction reference and idempotency key.
- `state`, `stage`, provider reference, and last error.
- Correlation id from the request and from the outbound call.
- Whether the circuit is open and whether a dead letter was recorded.
