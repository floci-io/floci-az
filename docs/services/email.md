# Azure Communication Services — Email

Compatible with the `azure-communication-email` SDK, the `Microsoft.Communication` ARM management
plane, and any HTTP client. Floci AZ emulates the ACS **Email** data plane (`POST /emails:send` plus
status polling) and **captures every message in-memory** for local inspection — a Mailpit-style
mailbox for your tests. No real email is ever delivered.

> **HTTP-only — no Docker.** Sending, status polling, inspection, and ARM resources are all
> in-process. There is no sidecar.

---

## Features

- **Send email** — `POST /emails:send` accepts the full ACS payload (`senderAddress`, `content`
  with `subject`/`plainText`/`html`, `recipients` `to`/`cc`/`bcc`, `attachments`, `replyTo`,
  `headers`) and returns `202 Accepted` with an `Operation-Location` header for polling
- **Operation status** — `GET /emails/operations/{operationId}` reports the long-running operation
  status; the emulator completes immediately, so it returns `Succeeded` with a `resourceLocation`
- **Inspection mailbox** — `GET /emailMessages` lists every captured message, `GET
  /emailMessages/{operationId}` returns one in full (including the original request body), and
  `DELETE /emailMessages` clears the mailbox
- **ARM management plane** — `Microsoft.Communication/communicationServices`,
  `.../emailServices`, and `.../emailServices/{name}/domains/{domain}` CreateOrUpdate, Get, Delete,
  and List

---

## Endpoints

```
POST   /emails:send                              # data-plane send → 202 + Operation-Location
GET    /emails/operations/{operationId}          # operation status (Succeeded)

GET    /emailMessages                            # list captured messages
GET    /emailMessages/{operationId}              # single captured message detail
DELETE /emailMessages                            # clear the mailbox

PUT/GET/DELETE .../providers/Microsoft.Communication/communicationServices/{name}
PUT/GET/DELETE .../providers/Microsoft.Communication/emailServices/{name}
PUT/GET/DELETE .../providers/Microsoft.Communication/emailServices/{name}/domains/{domain}
```

The send and polling paths are also served when the SDK targets the ACS host form
`https://{resource}.communication.azure.com` (use `FLOCI_AZ_HOSTNAME`/your hosts file to point that
name at the emulator), or via the path-style `/{account}-email/` suffix.

---

## Quickstart

### Send an email (Python SDK)

```python
from azure.communication.email import EmailClient

# In dev mode the access key is not validated
client = EmailClient.from_connection_string(
    "endpoint=http://localhost:4577/;accesskey=<any-base64-key>")

poller = client.begin_send({
    "senderAddress": "DoNotReply@example.com",
    "content": {"subject": "Hello", "plainText": "Hi from floci-az!"},
    "recipients": {"to": [{"address": "dev@example.com"}]},
})
result = poller.result()
print(result["id"], result["status"])   # operationId, Succeeded
```

### Send with curl

```bash
curl -s -X POST "http://localhost:4577/emails:send?api-version=2023-03-31" \
  -H "Content-Type: application/json" \
  -d '{
    "senderAddress": "DoNotReply@example.com",
    "content": {"subject": "Hello", "plainText": "Hi!"},
    "recipients": {"to": [{"address": "dev@example.com"}]}
  }'
# → 202 Accepted, Operation-Location: .../emails/operations/{operationId}
```

### Inspect the captured mailbox

```bash
# List everything that was "sent"
curl -s "http://localhost:4577/emailMessages"
# → {"value":[{"operationId":"...","status":"Succeeded","subject":"Hello","toCount":1, ...}], "count":1}

# Fetch one message in full (includes the original request body)
curl -s "http://localhost:4577/emailMessages/<operationId>"

# Clear the mailbox between tests
curl -s -X DELETE "http://localhost:4577/emailMessages"
```

This makes it easy to assert in tests that your code sent the right subject, recipients, and body —
without an SMTP server or a real ACS resource.

---

## Configuration

```yaml
floci-az:
  services:
    email:
      enabled: true
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_EMAIL_ENABLED` | `true` | Enable/disable the service |

---

## Notes & limitations

- **No delivery.** Messages are captured in-memory only; nothing is sent over SMTP and no external
  recipient receives anything. The mailbox is not persisted and is cleared on restart.
- **Operations always succeed immediately.** There is no `Running`→`Succeeded` transition delay and
  no failure simulation.
- **Auth is permissive.** The connection-string access key and `Authorization` header are accepted
  but not validated (dev mode), matching the rest of the emulator.
- **ARM resources are state-only.** `communicationServices`/`emailServices`/`domains` return
  `Succeeded` provisioning state with synthesized properties (`hostName`, `dataLocation`); domain
  verification (SPF/DKIM TXT records) is not emulated.
