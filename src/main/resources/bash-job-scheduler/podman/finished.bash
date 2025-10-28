# give a second for the container to complete by itself
sleep 1

bash -c "$ENV_PREFIX_PODMAN_SOCKET -s --fail-with-body -XDELETE -H content-type:application/json http://d/v4.0.0/libpod/containers/$POD_NAME"
