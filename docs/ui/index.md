# Web Console

floci-az can start [Floci UI](https://github.com/floci-io/floci-ui), a browser console for the resources in
your local emulator. It runs as a sidecar container that floci-az starts the first time you ask for it.

**Open it at:** `http://localhost:4577/_floci/ui`

The first request starts the console container, so you land on a short "starting the console" page that
redirects as soon as the console is ready. Later requests redirect straight away.

Opening `http://localhost:4577/` in a browser shows the floci-az dashboard: the endpoint and connection
snippets, the enabled services and the console's state, with an **Open Floci UI** button. Requests at `/` that
do not ask for HTML, such as the Cosmos DB SDK reading its account, are answered as before.

## How it works

floci-az starts nothing at boot. On the first `/_floci/ui` request it:

1. pulls the console image if it is not present locally,
2. removes a leftover `floci-az-ui` container from an earlier run,
3. starts the console with `-p 4500:4500` on floci-az's Docker network, handing it floci-az's address and
   Azure defaults (see [Console Contract v2](console-contract.md)),
4. polls the console until it reports that it can reach floci-az,
5. redirects your browser to the console's Azure view, `http://<host>:4500/console/azure`.

The redirect keeps the host name you used to reach floci-az and swaps in the console's port.

If the console container is removed or stops, the next status check starts it again. When floci-az stops,
it removes the console container, unless `FLOCI_AZ_UI_KEEP_RUNNING_ON_SHUTDOWN=true`.

## Ports

Port `4500` is published on the host by Docker, not by floci-az, so it needs **no** `ports:` entry in your
`docker-compose.yml`. See [Ports Reference](../configuration/ports.md).

floci-aws's web console uses port `4500` too. To run both emulators' consoles at once, move one of them:

```yaml
environment:
  FLOCI_AZ_UI_PORT: "4501"
```

If the port is taken, the starting page says so and names the variable to change.

## Configuration

All keys live under `floci-az.ui.*`, so `FLOCI_AZ_UI_*` as environment variables.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_UI_ENABLED` | `true` | Allow `/_floci/ui` to start the console |
| `FLOCI_AZ_UI_IMAGE` | `floci/floci-ui:0.7.0` | Console image to run |
| `FLOCI_AZ_UI_PORT` | `4500` | Host port the console is published on |
| `FLOCI_AZ_UI_INTERNAL_PORT` | `4500` | Port the console listens on inside its container, passed to it as `PORT` |
| `FLOCI_AZ_UI_ENDPOINT` | _(derived)_ | URL the console uses to reach floci-az, instead of deriving it |
| `FLOCI_AZ_UI_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave the console running when floci-az stops |

The console joins the network in `FLOCI_AZ_SERVICES_DOCKER_NETWORK`, like every other sidecar.

### How the console reaches floci-az

Unless `FLOCI_AZ_UI_ENDPOINT` is set, floci-az derives the address:

- **floci-az in Docker:** floci-az's own address on the shared network and its port, for example
  `http://172.18.0.5:4577`.
- **floci-az on the host:** the Docker host as seen from a container (`host.docker.internal` on Docker Desktop)
  and floci-az's port, for example `http://host.docker.internal:4577`.

`FLOCI_AZ_HOSTNAME` is not used for this: it names floci-az as clients see it, which behind a reverse proxy is
not an address the console container can reach.

Set `FLOCI_AZ_UI_ENDPOINT` when neither works, for example with `FLOCI_AZ_DOCKER_ENDPOINT_MODE=published` on a
remote Docker daemon. floci-az logs a warning in that mode.

## Troubleshooting

| What you see | Cause and fix |
|---|---|
| "Host port 4500 is already in use" | Another console, often floci-aws's, holds the port. Set `FLOCI_AZ_UI_PORT` |
| "Floci UI couldn't start" with a connection error | The console runs but cannot reach floci-az at the derived address. Set `FLOCI_AZ_UI_ENDPOINT` to a URL the console container can reach. **Open Floci UI anyway** still opens the running console |
| "The web console is disabled" | `FLOCI_AZ_UI_ENABLED=false` |
| "Floci UI is taking too long" | The page gives up after 5 minutes. Check that Docker is running and the image can be pulled, and read `docker logs floci-az-ui` |

## Running a different console

Any console implementing the [Console Contract v2](console-contract.md) works with nothing but an image name:

```yaml
environment:
  FLOCI_AZ_UI_IMAGE: acme/my-console:1.0
```
