if [ -z "$ENV_PREFIX_PODMAN_SOCKET" ]; then
    ENV_PREFIX_PODMAN_SOCKET="curl --unix-socket /run/user/$UID/podman/podman.sock"
fi

if ! state="$(bash -c "$ENV_PREFIX_PODMAN_SOCKET -s --fail-with-body -H content-type:application/json http://d/v4.0.0/libpod/containers/$POD_NAME/json | jq '.State'")"; then
    echo "failed to inspect container $state"
fi

if [ $(echo "$state" | jq '.Running') == "true" ]; then
    exit 0
elif [ $(echo "$state" | jq '.OOMKilled') == "true" ]; then
    # now in Ubuntu 22.04 OOM killer seems to kill the python and comp can take care of the results, but let's check this 
    # in any case if this changes in the future
    echo "OOMKilled"
    exit 1
else
    if ! bash -c "$ENV_PREFIX_PODMAN_SOCKET -s --fail-with-body http://d/v4.0.0/libpod/images/$IMAGE/exists"; then
        echo "image not found, waiting it to be pulled: $IMAGE"
        exit 0
    else
        echo "image found: $IMAGE"
    fi
    echo "unknown state: $state"
    exit 1
fi
