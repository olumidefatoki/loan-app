/**
 * Transactional outbox, idempotent inbox, and the core banking adapter.
 * The foundation tables are in V1. Core banking instructions are in V10.
 * Publishers call the adapter. They do not call a core banking vendor URL.
 */
package com.mfb.lending.module.integration;
