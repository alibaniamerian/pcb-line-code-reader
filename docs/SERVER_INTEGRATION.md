# Production-software integration

Status: proposal for the next implementation stage. No endpoint or transport is
implemented in this release; the following JSON is not an existing API.

## Where to extend the app

In `src/org/datamatrix/linereader/MainActivity.java`, the serial decode executor
receives verified text from `LineDecoder` or `MarkedDecoder`. The UI callback
checks camera generation and setting epoch before displaying it. Extract a
single `onVerifiedRead(format, text, capturedAt)` callback at this point.

Keep this callback downstream of ECC/QR validation and coordinate/epoch checks.
The custom DataMatrix analyzer should remain unchanged when adding transport.
Store a scan event before sending it; perform network I/O on a separate executor,
not the camera, UI or decode executor.

## Proposed request

```json
{
  "event_id": "a client-generated UUID",
  "device_id": "configured scanner identity",
  "station_id": "configured production station",
  "format": "DATA_MATRIX",
  "value": "the exact verified payload",
  "captured_at": "an ISO-8601 UTC timestamp"
}
```

`format` is DATA_MATRIX or QR_CODE. Preserve text exactly, including leading
zeroes. Do not include image pixels by default. Assign an event ID once and reuse
it for all retries of that event. The server should process that ID idempotently.

## Proposed response

```json
{
  "event_id": "the request event ID",
  "status": "accepted",
  "message": "a message suitable for the operator"
}
```

Business status should distinguish accepted and rejected. A network timeout is
neither. A delayed response must update only its matching event, even if the
camera has already read another board. Invalid/unknown responses must not mark
a board accepted.

## Operator states

Add visible states for decoded, awaiting acknowledgement, accepted, rejected
and pending retry. Do not reuse the current local-decode green flash to imply
server acceptance. Keep a durable outbox and a visible pending count if offline
operation is required. Retry with bounded backoff using the original event ID.

## Decisions required from the production-software team

- Endpoint and transport, authentication, device/station provisioning.
- Request/response field names, business rejection codes and timeout policy.
- Whether repeated views of the same code represent one board or multiple boards.
- The rearm trigger: code disappearance, physical sensor, operator action or a
  server instruction. A time-only duplicate filter may hide legitimate boards.
- Whether the line must stop on rejected or unacknowledged reads.
- Text versus binary payload support and data-retention requirements.

## Integration acceptance tests

Verify a valid read, explicit rejection, timeout, disconnected network, app
restart with a pending event, retry without duplicate processing, delayed replies
and two consecutive boards bearing the same value. Confirm that changing format
or square size cannot publish an obsolete in-flight result.
