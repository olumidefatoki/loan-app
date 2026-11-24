/**
 * Append-only, hash-chained audit trail. Authentication and administration write security events through {@code AuditService}.
 * Payloads are scrubbed of passwords, tokens, and raw identity numbers before they are stored.
 */
package com.mfb.lending.module.audit;
