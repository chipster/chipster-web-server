if [ -z "$ENV_PREFIX_PODMAN_SOCKET" ]; then
    ENV_PREFIX_PODMAN_SOCKET="curl --unix-socket /run/user/$UID/podman/podman.sock"
fi

bash -c "$ENV_PREFIX_PODMAN_SOCKET -s -XPOST -H content-type:application/json http://d/v4.0.0/libpod/containers/$POD_NAME/kill"
