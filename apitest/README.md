# API tests

Black-box checks against a running service, complementing the Java test suite.

| Tool | What it checks | File |
|---|---|---|
| Newman | The booking flow end to end: catalogue, Holds, seat conflicts, one Pending Booking per Customer, payment, cancellation and refunds, access rules, and known bugs | `booking-flow.postman_collection.json` |
| Schemathesis | Every endpoint in `docs/openapi.json` with generated inputs: no 500, responses match the schema | (no file; command below) |

Both need the stack running and write data, so run them on a database you can reset (see `loadtest/locust/README.md`), never during a load test.

## Newman

```bash
npm install -g newman
set -a; source .env; set +a
newman run apitest/booking-flow.postman_collection.json \
  --env-var baseUrl=http://localhost:18080 \
  --env-var adminEmail="$ADMIN_EMAIL" --env-var adminPassword="$ADMIN_PASSWORD"
```

The folder "Known bugs (phase 2)" fails today on purpose. Each request there names a problem from `docs/phase2/problem-register.md` and passes once that problem is fixed.

## Schemathesis

```bash
pip install schemathesis
set -a; source .env; set +a
TOKEN=$(curl -s -X POST localhost:18080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .accessToken)
schemathesis run docs/openapi.json --url http://localhost:18080 -H "Authorization: Bearer $TOKEN" \
  --checks all --max-examples 50
```

Run it once with the Admin token and once with a Customer token, since access rules change the responses. A 500 is always a bug; a schema mismatch means either the code or `docs/openapi.json` is wrong.
