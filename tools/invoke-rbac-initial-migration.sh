#!/usr/bin/env bash
# Run only the small offline CLI, never a second Spring Boot server. The application
# image supplies Java 17; the verified candidate JAR is mounted over no existing image file.
set -Eeuo pipefail
umask 077

if [[ $# -lt 3 ]]; then
    printf '%s\n' 'Usage: RBAC_INITIAL_JAR_SHA256=<sha256> invoke-rbac-initial-migration.sh <candidate-app.jar> <jdbc-url> inspect ... | <plan.json> [--execute <sha256>]' >&2
    exit 2
fi

jar=$1
jdbc_url=$2
shift 2
case "$jar" in
    /*) ;;
    *) jar="$(pwd)/$jar" ;;
esac
[[ -f "$jar" && ! -L "$jar" ]] || { printf '%s\n' 'Candidate release app.jar must be a regular file' >&2; exit 2; }
[[ "$jdbc_url" == jdbc:mysql://127.0.0.1:3306/* ]] || {
    printf '%s\n' 'JDBC must use 127.0.0.1:3306 inside the shared MySQL network namespace' >&2
    exit 2
}

expected_jar_sha256="${RBAC_INITIAL_JAR_SHA256:-}"
[[ "$expected_jar_sha256" =~ ^[0-9a-f]{64}$ ]] || {
    printf '%s\n' 'RBAC_INITIAL_JAR_SHA256 must contain the reviewed candidate JAR SHA256' >&2
    exit 2
}
actual_jar_sha256="$(sha256sum "$jar")"
actual_jar_sha256="${actual_jar_sha256%% *}"
[[ "$actual_jar_sha256" == "$expected_jar_sha256" ]] || {
    printf '%s\n' 'Candidate JAR SHA256 mismatch; refusing to use a stale or unreviewed JAR' >&2
    exit 2
}

mysql_container="${RBAC_MYSQL_CONTAINER:-jualncampus-mysql}"
[[ -n "${RBAC_INITIAL_DB_USER:-}" && -n "${RBAC_INITIAL_DB_PASSWORD:-}" ]] || {
    printf '%s\n' 'Database credentials must be supplied only through RBAC_INITIAL_DB_USER/PASSWORD' >&2
    exit 2
}
app_container="${RBAC_APP_CONTAINER:-jualncampus-app}"
[[ "$mysql_container" =~ ^[A-Za-z0-9_.-]+$ && "$app_container" =~ ^[A-Za-z0-9_.-]+$ ]] || {
    printf '%s\n' 'Invalid MySQL or application container name' >&2
    exit 2
}
[[ "$(docker container inspect --format '{{.State.Running}}' "$mysql_container")" == 'true' ]] || {
    printf '%s\n' 'MySQL container must be running' >&2
    exit 2
}

if [[ -n "${RBAC_JAVA_IMAGE:-}" ]]; then
    java_image=$RBAC_JAVA_IMAGE
else
    java_image="$(docker container inspect --format '{{.Config.Image}}' "$app_container")"
fi
[[ -n "$java_image" ]] || { printf '%s\n' 'Unable to resolve the Java 17 application image' >&2; exit 2; }
docker image inspect "$java_image" >/dev/null

container_name="jualn-rbac-initial-$$-$RANDOM"
docker_args=(
    run --rm --name "$container_name"
    --network "container:$mysql_container"
    --memory "${RBAC_INITIAL_MEMORY:-384m}"
    --memory-swap "${RBAC_INITIAL_MEMORY_SWAP:-512m}"
    --cpus 1
    --cap-drop ALL
    --security-opt no-new-privileges
    --read-only
    --tmpfs /tmp:rw,noexec,nosuid,size=64m
    --user "$(id -u):$(id -g)"
    --env RBAC_INITIAL_DB_USER
    --env RBAC_INITIAL_DB_PASSWORD
    --volume "$jar:/candidate/app.jar:ro"
    --entrypoint java
)

java_args=(
    -Xms32m -Xmx192m -XX:ReservedCodeCacheSize=32m -XX:+UseSerialGC
    -Dloader.main=cn.jualn.miniapp.module.authorization.service.RbacInitialMigration
    -cp /candidate/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
    "$jdbc_url"
)

# Mount only explicit plan/approval files, never a host directory or the running old JAR.
for argument in "$@"; do
    if [[ -f "$argument" ]]; then
        case "$argument" in
            /*) host_file=$argument ;;
            *) host_file="$(pwd)/$argument" ;;
        esac
        [[ ! -L "$host_file" ]] || { printf '%s\n' 'Plan and approval files must not be symbolic links' >&2; exit 2; }
        docker_args+=(--volume "$host_file:$host_file:ro")
        java_args+=("$host_file")
    else
        java_args+=("$argument")
    fi
done

exec docker "${docker_args[@]}" "$java_image" "${java_args[@]}"
