#!/usr/bin/env python3
"""Publish the springdoc export as the client contract and the catalogues.

Reads target/openapi/lending-v1.json, which OpenApiExportIT writes from the
running v1 group. Adds import metadata and representative examples. Does not
change Java response types.
"""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "target" / "openapi" / "lending-v1.json"
OUT = Path(__file__).resolve().parent
SPEC_PATH = OUT / "openapi.json"

DOWNLOADS = {
    "/api/v1/customers/{id}/documents/{documentId}",
    "/api/v1/collaterals/{id}/documents/{documentId}",
    "/api/v1/guarantors/{id}/documents/{documentId}",
    "/api/v1/reports/exports/{id}/download",
}

MONEY_HINTS = (
    "amount",
    "principal",
    "interest",
    "fee",
    "balance",
    "penalty",
    "rate",
    "premium",
    "turnover",
    "income",
    "expense",
    "cash",
    "liabilit",
    "repayment",
    "disbursement",
    "outstanding",
    "overdue",
    "par",
    "coverage",
    "charge",
    "tax",
)


def main() -> None:
    spec = json.loads(SOURCE.read_text())
    prepare(spec)
    SPEC_PATH.write_text(json.dumps(spec, indent=2) + "\n")
    write_endpoints(spec)
    write_schemas(spec)
    write_errors()
    print(f"wrote {SPEC_PATH} operations={count_operations(spec)} schemas={len(spec['components']['schemas'])}")


def prepare(spec: dict) -> None:
    spec["servers"] = [
        {
            "url": "{baseUrl}",
            "description": "Environment origin. The live /v3/api-docs/v1 document uses a relative server URL instead.",
            "variables": {"baseUrl": {"default": "http://localhost:8080"}},
        }
    ]
    info = spec.setdefault("info", {})
    info["description"] = (
        (info.get("description") or "")
        + "\n\nThis file is the importable contract for Postman and typed clients. "
        + "It is exported from the springdoc v1 group and then annotated with the error envelope, "
        + "a base URL variable, and representative examples. Monetary fields remain JSON numbers, "
        + "which is the implemented Jackson contract."
    )
    for path, item in spec["paths"].items():
        for method, operation in list(item.items()):
            if method not in {"get", "post", "put", "patch", "delete"}:
                continue
            alias_json(operation)
            if method == "get" and path in DOWNLOADS:
                mark_binary(operation)
    add_examples(spec)


def alias_json(operation: dict) -> None:
    for response in (operation.get("responses") or {}).values():
        content = response.get("content") or {}
        star = content.get("*/*")
        if not star or "application/json" in content:
            continue
        schema = star.get("schema") or {}
        if schema.get("format") in {"byte", "binary"}:
            continue
        content["application/json"] = json.loads(json.dumps(star))


def mark_binary(operation: dict) -> None:
    response = (operation.get("responses") or {}).get("200")
    if not response:
        return
    response["content"] = {
        "application/octet-stream": {
            "schema": {"type": "string", "format": "binary"},
            "description": (
                "Raw file bytes, not a base64 JSON string. The response Content-Type is the stored "
                "file type. Uploads are PDF, JPEG, or PNG. Report exports are CSV, Excel, or PDF."
            ),
        }
    }


def add_examples(spec: dict) -> None:
    login = spec["paths"]["/api/v1/auth/login"]["post"]
    put_request(login, "customerLogin", {
        "identifier": "ada@example.com",
        "password": "Str0ng-Passw0rd!",
    })
    put_response(login, "200", "tokens", {
        "success": True,
        "code": "OK",
        "message": "Success",
        "data": {
            "tokenType": "Bearer",
            "accessToken": "eyJhbGciOiJIUzI1NiJ9.access",
            "refreshToken": "refresh-token-value",
            "expiresIn": 900,
            "sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "mfaRequired": False,
        },
        "correlationId": "4f3c2a10-6b1e-4d2a-9c11-0a9b8c7d6e5f",
        "timestamp": "2026-11-24T13:00:00Z",
    })
    refresh = spec["paths"]["/api/v1/auth/refresh-token"]["post"]
    put_request(refresh, "rotate", {"refreshToken": "refresh-token-value"})
    put_response(refresh, "200", "rotated", {
        "success": True,
        "code": "OK",
        "message": "Success",
        "data": {
            "tokenType": "Bearer",
            "accessToken": "eyJhbGciOiJIUzI1NiJ9.rotated",
            "refreshToken": "replacement-refresh-token",
            "expiresIn": 900,
            "sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "mfaRequired": False,
        },
        "correlationId": "4f3c2a10-6b1e-4d2a-9c11-0a9b8c7d6e5f",
        "timestamp": "2026-11-24T13:15:00Z",
    })
    register = spec["paths"]["/api/v1/auth/register"]["post"]
    put_request(register, "individual", {
        "customerType": "INDIVIDUAL",
        "fullName": "Ada Okonkwo",
        "email": "ada@example.com",
        "phoneNumber": "+2348012345678",
        "password": "Str0ng-Passw0rd!",
    })
    repay = spec["paths"]["/api/v1/repayments"]["post"]
    put_request(repay, "repayment", {
        "loanAccountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "amount": 25000.00,
        "channel": "PAYMENT_GATEWAY",
        "idempotencyKey": "mobile-20261124-0001",
        "valueDate": "2026-11-24",
        "externalReference": "PGW-10001",
    })
    export = spec["paths"]["/api/v1/reports/exports"]["post"]
    accepted = json.loads(json.dumps(export["responses"]["200"]))
    accepted["description"] = "The extract is larger than the synchronous threshold and is still being generated."
    export["responses"]["202"] = accepted
    put_request(export, "portfolioCsv", {
        "report": "LOAN_PORTFOLIO",
        "format": "CSV",
        "startDate": "2026-11-01",
        "endDate": "2026-11-24",
    })
    put_response(export, "202", "accepted", {
        "success": True,
        "code": "OK",
        "message": "Success",
        "data": {
            "id": "8d6c1c2a-1b2c-4d3e-9f00-112233445566",
            "report": "LOAN_PORTFOLIO",
            "format": "CSV",
            "status": "PENDING",
            "expiresAt": "2026-11-25T13:00:00Z",
            "downloadPath": "/api/v1/reports/exports/8d6c1c2a-1b2c-4d3e-9f00-112233445566/download",
        },
        "correlationId": "4f3c2a10-6b1e-4d2a-9c11-0a9b8c7d6e5f",
        "timestamp": "2026-11-24T13:00:00Z",
    })


def put_request(operation: dict, name: str, value: dict) -> None:
    content = operation["requestBody"]["content"]["application/json"]
    content.setdefault("examples", {})[name] = {"summary": name, "value": value}


def put_response(operation: dict, status: str, name: str, value: dict) -> None:
    content = operation["responses"][status]["content"]
    media = content.get("application/json") or content.get("*/*")
    media.setdefault("examples", {})[name] = {"summary": name, "value": value}


def count_operations(spec: dict) -> int:
    return sum(
        1
        for item in spec["paths"].values()
        for method in item
        if method in {"get", "post", "put", "patch", "delete"}
    )


def write_endpoints(spec: dict) -> None:
    grouped: dict[str, list[str]] = defaultdict(list)
    rows = []
    for path in sorted(spec["paths"]):
        item = spec["paths"][path]
        for method in ("get", "post", "put", "patch", "delete"):
            operation = item.get(method)
            if not operation:
                continue
            tag = (operation.get("tags") or ["Untagged"])[0]
            security = "Public" if not operation.get("security") else "Bearer"
            params = [
                f"{parameter.get('name')} ({parameter.get('in')})"
                for parameter in operation.get("parameters") or []
                if parameter.get("name") != "X-Correlation-Id"
            ]
            body = ""
            request = operation.get("requestBody") or {}
            if request:
                body = ", ".join(sorted((request.get("content") or {}).keys()))
            success = ", ".join(
                code for code in (operation.get("responses") or {}) if code.startswith("2")
            )
            summary = (operation.get("summary") or "").replace("|", "/")
            line = (
                f"| `{method.upper()}` | `{path}` | {security} | {success or '—'} | "
                f"{summary} |"
            )
            grouped[tag].append(line)
            rows.append((tag, method, path, security, params, body))
    parts = [
        "# API endpoint catalogue",
        "",
        "Source: `docs/api/openapi.json`, exported from the running springdoc group `v1`.",
        f"{count_operations(spec)} operations on {len(spec['paths'])} paths.",
        "Every operation accepts optional `X-Correlation-Id` and documents the error envelope.",
        "Audience is the channel that may call the route. Customer and staff share a route when the service authorises both.",
        "",
    ]
    for tag in sorted(grouped):
        parts.append(f"## {tag}")
        parts.append("")
        parts.append("| Method | Path | Access | Success | Summary |")
        parts.append("| --- | --- | --- | --- | --- |")
        parts.extend(grouped[tag])
        parts.append("")
    (OUT / "endpoint-catalogue.md").write_text("\n".join(parts))


def write_schemas(spec: dict) -> None:
    schemas = spec["components"]["schemas"]
    parts = [
        "# Request and response schema catalogue",
        "",
        "Source: `docs/api/openapi.json`. Names are the springdoc schema names.",
        "A monetary property is a JSON number in this contract. A currency property is a separate string.",
        "Success envelopes are `ApiResponse*`. Failures use `ApiError`.",
        "",
        "## Envelope shapes",
        "",
        "| Shape | Properties |",
        "| --- | --- |",
        "| Success | `success`, `code`, `message`, `data`, `correlationId`, `timestamp` |",
        "| Error | `success`, `code`, `message`, `errors`, `correlationId`, `timestamp`, `path` |",
        "| PageResponse | `items`, `page`, `size`, `total` |",
        "| CustomerLoanPage | `loans`, `page`, `size`, `total` |",
        "| CustomerRepaymentPage | `repayments`, `page`, `size`, `total`, and period fields |",
        "| DetailPage | `lines`, `page`, `size`, `total` |",
        "| AuditPage | `items`, `page`, `size`, `total`, `chainIntact` |",
        "",
        "`page` is zero-based. `size` is from 1 to 100 on endpoints that use `PageBounds`.",
        "",
        "## Schemas",
        "",
    ]
    money_rows = []
    for name in sorted(schemas):
        schema = schemas[name]
        properties = schema.get("properties") or {}
        required = set(schema.get("required") or [])
        if not properties and schema.get("enum"):
            parts.append(f"### `{name}`")
            parts.append("")
            parts.append("Enum: " + ", ".join(f"`{value}`" for value in schema["enum"]))
            parts.append("")
            continue
        if not properties:
            continue
        parts.append(f"### `{name}`")
        parts.append("")
        if schema.get("description"):
            parts.append(schema["description"].split("\n")[0])
            parts.append("")
        parts.append("| Property | Type | Required |")
        parts.append("| --- | --- | --- |")
        for prop in properties:
            definition = properties[prop]
            parts.append(f"| `{prop}` | {type_name(definition)} | {'yes' if prop in required else 'no'} |")
            if definition.get("type") == "number" and any(hint in prop.lower() for hint in MONEY_HINTS):
                money_rows.append(f"| `{name}` | `{prop}` | number |")
        parts.append("")
    parts.extend([
        "## Monetary properties",
        "",
        "These properties are JSON numbers. They are not decimal strings.",
        "",
        "| Schema | Property | JSON type |",
        "| --- | --- | --- |",
        *money_rows,
        "",
    ])
    (OUT / "schema-catalogue.md").write_text("\n".join(parts) + "\n")


def type_name(schema: dict) -> str:
    if "$ref" in schema:
        return f"`{schema['$ref'].rsplit('/', 1)[-1]}`"
    if schema.get("type") == "array":
        return f"array of {type_name(schema.get('items') or {})}"
    kind = schema.get("type") or "object"
    if schema.get("format"):
        kind += f" ({schema['format']})"
    if schema.get("enum"):
        kind += " enum"
    return kind


def write_errors() -> None:
    text = """# Error code catalogue

Every unsuccessful JSON response uses `ApiError`:

```json
{
  "success": false,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "errors": [
    {"field": "amount", "message": "must be greater than or equal to 0.01"}
  ],
  "correlationId": "4f3c2a10-6b1e-4d2a-9c11-0a9b8c7d6e5f",
  "timestamp": "2026-11-24T13:00:00Z",
  "path": "/api/v1/repayments"
}
```

`code` is the `ErrorCode` enum name. `errors` is empty when the failure is not a field violation. Clients branch on `success` and then on `code`. The success envelope has `data` and does not have `errors` or `path`.

| Code | HTTP status | When it is returned |
| --- | --- | --- |
| `OK` | 200, 201, 202 | Success envelope only. It is not an error. |
| `VALIDATION_ERROR` | 400 | Bean validation or a named field violation. `errors` lists the fields. |
| `BAD_REQUEST` | 400, 405, 415 | The request is malformed, the method is not allowed, or the media type is unsupported. |
| `DOCUMENT_REJECTED` | 400 | The upload is missing, unreadable, or not an allowed PDF, JPEG, or PNG. |
| `MALWARE_DETECTED` | 400 | The malware scanner rejected the upload. |
| `UNAUTHORIZED` | 401 | The access token is missing or not valid. |
| `TOKEN_EXPIRED` | 401 | The access token is expired. Refresh it. Do not treat this as a logout. |
| `INVALID_CREDENTIALS` | 401 | The identifier or password does not match. |
| `OTP_INVALID` | 401 | The one-time code is wrong. |
| `OTP_EXPIRED` | 401 | The one-time code has expired. |
| `OTP_REUSED` | 401 | The one-time code was already consumed. |
| `MFA_INVALID` | 401 | The staff authenticator code is wrong. |
| `FORBIDDEN` | 403 | The token is valid and the action is not allowed. A customer asking for another customer's resource often receives `RESOURCE_NOT_FOUND` instead. |
| `ACCOUNT_DISABLED` | 403 | The account is disabled. |
| `ACCOUNT_NOT_VERIFIED` | 403 | Email or phone verification is still required. |
| `PASSWORD_EXPIRED` | 403 | The password must be reset before sign-in completes. |
| `MFA_ENROLLMENT_REQUIRED` | 403 | Staff MFA is not enrolled. |
| `ACCOUNT_LOCKED` | 423 | Too many failed sign-in attempts. |
| `RESOURCE_NOT_FOUND` | 404 | The identifier does not exist, or it is outside the caller's scope. |
| `CONFLICT` | 409 | The current state does not allow the action, including a duplicate key. |
| `DUPLICATE_CUSTOMER` | 409 | The user is already linked to a customer. |
| `COMPLIANCE_HOLD` | 409 | A blacklist or open compliance hold blocks the action. |
| `TOKEN_REVOKED` | 409 | The refresh token or session was revoked. |
| `TOKEN_REUSE` | 409 | A rotated refresh token was presented again. The session family is revoked. |
| `RATE_LIMITED` | 429 | The attempt window is exhausted. |
| `SERVICE_UNAVAILABLE` | 503 | A dependency needed to enforce the limit or complete the call is unavailable. |
| `DATABASE_ERROR` | 500 | The database rejected the request. The client message is generic. |
| `INTERNAL_ERROR` | 500 | An unexpected failure. The client message is generic. |

File downloads and the agreement PDF return the same JSON envelope when they fail. A successful download is raw bytes and has no envelope.

Several business rules share `CONFLICT` or `VALIDATION_ERROR`. The `message` and `errors` explain the rule. Adding a new code is backward compatible. Renaming a code is not.
"""
    (OUT / "error-catalogue.md").write_text(text)


if __name__ == "__main__":
    main()
