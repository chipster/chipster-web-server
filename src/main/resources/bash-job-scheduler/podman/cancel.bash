bash -c "$ENV_PREFIX_PODMAN_SOCKET -s -XPOST -H content-type:application/json http://d/v4.0.0/libpod/containers/$POD_NAME/kill"
