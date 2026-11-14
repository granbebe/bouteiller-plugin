# Bouteiller plugin

Jenkins plugin of [Bouteiller](https://bouteiller.mannocci.fr), the Jenkins client for iPhone and iPad. Optional: the
app works without it. It brings:

- **Push notifications**: build started, succeeded, failed (with the failed stage of a Pipeline), unstable, fixed,
  aborted, and input pending, sent within seconds even when the device cannot reach Jenkins (no VPN needed on the
  phone).
- **A stable API** for the stages and pending inputs of Pipeline builds, computed from the flow graph: the same
  whatever display plugins (Stage View, Graph View) are installed.

Baseline: Jenkins 2.580.1, Java 21. No required plugin dependency: the Pipeline features need `workflow-job` (and
`pipeline-input-step` for inputs), and are off without them.

## How push works

Each device registers with the plugin, using the credentials of its Jenkins user: APNs device token, an AES-256 key
generated on the device, and an access token issued by the relay. For each event, the plugin encrypts the event with
the key of each subscribed device (AES-GCM) and posts it to the **APNs signing relay** of the app, which signs the
request with the APNs key of the app and forwards it to Apple. The relay sees device tokens and encrypted content
only; the notification service extension of the app decrypts the event on the device.

The plugin never holds an APNs key: it cannot send anything to a device that did not register with it, and the relay
access token only lets it notify that device.

## Configuration

*Manage Jenkins › System › Bouteiller*: turn push notifications on or off; under *Advanced*, the relay URL (default
`https://bouteiller-relay.mannocci.fr`), the use of the Jenkins HTTP proxy, and a *Test the relay* button.

*Manage Jenkins › Bouteiller devices*: registered devices per user, with revocation.

A user only receives events of jobs they can read. Registrations are stored in
`JENKINS_HOME/io.jenkins.plugins.bouteiller.RegistrationStore.xml`, tokens and keys encrypted with Jenkins secrets.

## REST API

Standard Jenkins authentication (user name and API token), prefix `/bouteiller/api/v1`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/info` | Plugin version, API version and features |
| `PUT` | `/registrations/{id}` | Create or update the registration of a device of the current user |
| `GET` | `/registrations/{id}` | State of the registration (`active`, `expired`) |
| `DELETE` | `/registrations/{id}` | Unregister |
| `GET` / `PUT` | `/registrations/{id}/subscriptions` | Read / replace the subscriptions |
| `POST` | `/registrations/{id}/test` | Send a test notification now |
| `GET` | `/job/{path}/{n}/stages` | Stages and parallel branches of a Pipeline build, with status, timing and agent |
| `GET` | `/job/{path}/{n}/inputs` | Pending inputs of a Pipeline build, with their parameters |

`{path}` is the path of the job as in the URLs of Jenkins (`job/folder/job/name`).

## Build

```sh
mvn verify          # build, tests, SpotBugs; produces target/bouteiller.hpi
mvn hpi:run         # local Jenkins on http://localhost:8080/jenkins/ with the plugin
```

Requires Java 21 or later and Maven 3.9 or later.

Install by hand: *Manage Jenkins › Plugins › Advanced settings › Deploy Plugin*, with `target/bouteiller.hpi`.

## Contributing

Issues and pull requests are welcome on GitHub. The iOS app and the relay are not open source; the API of the plugin
and the format of the events are part of this repository.

## License

MIT, see [LICENSE](LICENSE).
