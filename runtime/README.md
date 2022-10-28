# Job Worker Connector Run-Time

A simple run-time to execute [connector functions](../core) as job workers.

## Running Connector Function(s)

We expose a main method to run one or more connectors and register them
as [Zeebe job workers](https://docs.camunda.io/docs/next/components/concepts/job-workers/).

### Connecting to Zeebe

You configure the connection to Zeebe using the
standard [Zeebe environment variables](https://docs.camunda.io/docs/apis-clients/java-client/#bootstrapping).

### Automatic Connector Discovery

The run-time picks up outbound connectors available on the classpath automatically unless [overriden through manual configuration](#manual-discovery).
It uses the default configuration specified through the `@OutboundConnector` annotation in these cases.

```bash
java -cp 'connector-runtime-VERSION.jar:connector-http-json-with-dependencies.jar' \
    io.camunda.connector.runtime.ConnectorRuntimeApplication
```

### Manual Discovery

Use environment variables to configure connectors and their configuration explicitly, without [auto-discovery](#automatic-connector-discovery):

| Environment variable                          | Purpose                                                       |
|:----------------------------------------------|:--------------------------------------------------------------|
| `CONNECTOR_{NAME}_FUNCTION` (required)        | Function to be registered as job worker with the given `NAME` |
| `CONNECTOR_{NAME}_TYPE` (optional)            | Job type to register for worker with `NAME`                   |
| `CONNECTOR_{NAME}_INPUT_VARIABLES` (optional) | Variables to fetch for worker with `NAME`                     |

Through that configuration you define all job workers to run.
Specifying optional values allow you to override `@OutboundConnector` provided connector configuration.

```bash
CONNECTOR_HTTPJSON_FUNCTION=io.camunda.connector.http.HttpJsonFunction
CONNECTOR_HTTPJSON_TYPE=non-default-httpjson-task-type

java -cp 'connector-runtime-VERSION.jar:connector-http-json-with-dependencies.jar' \
    io.camunda.connector.runtime.ConnectorRuntimeApplication
```

### Docker

#### Docker Job Worker Runtime Image

The [`Dockerfile`](./Dockerfile) in this repository provides a base image
including the job worker runtime. The image starts the job worker runtime with
all `jar` files provided in the `/opt/app` directory as classpath.

To use the image at least one connector has to be added to the classpath. We recommend to provide jars with all dependencies bundled.

> :warning: As all connectors share a single classpath it can happen that
> different versions of the same dependency are available which can lead to
> conflicts. To prevent this, common dependencies like `jackson` can be shaded and
> relocated inside the connector jar.

Example adding a connector jar by extending the image

```dockerfile
FROM camunda/connectors:0.2.2

ADD https://repo1.maven.org/maven2/io/camunda/connector/connector-http-json/0.9.0/connector-http-json-0.9.0-with-dependencies.jar /opt/app/
```

Example adding a connector jar by using volumes

```bash
docker run --rm --name=connectors -d -v $PWD/connector.jar:/opt/app/ camunda/connectors:0.2.2
```

## Local secrets

To inject secrets during connector function execution, export them as environment variables

```bash
export MY_SECRET='foo'
```

Reference the secret in the request payload prefixed with `secrets.MY_SECRET`.

### Docker Image Secrets

To inject secrets into the [docker images of the runtime](#docker), they have to be available in the environment of the docker container.

For example, you can inject secrets when running a container:

```bash
docker run --rm --name=connectors -d \
           -v $PWD/connector.jar:/opt/app/ \  # Add a connector jar to the classpath
           -e MY_SECRET=secret \              # Set a secret with value
           -e SECRET_FROM_SHELL \             # Set a secret from the environment
           --env-file secrets.txt \           # Set secrets from a file
           camunda/connectors:0.2.2
```

The secret `MY_SECRET` value is specified directly in the `docker run` call,
whereas the `SECRET_FROM_SHELL` is injected based on the value in the
current shell environment when `docker run` is executed. The `--env-file`
option allows using a single file with the format `NAME=VALUE` per line
to inject multiple secrets at once.

## Build

```bash
mvn clean package
```