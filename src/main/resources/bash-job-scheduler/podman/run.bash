# old images have symlink in /opt/chipster/tools and podman doesn't allow mounting on top of it
if bash -c "$ENV_PREFIX_PODMAN_SOCKET -s --fail-with-body http://d/v4.0.0/libpod/images/$IMAGE/exists"; then
    echo "image exists: $IMAGE"
else
    echo "pull image $IMAGE"
    bash -c "$ENV_PREFIX_PODMAN_SOCKET -XPOST -s --fail-with-body http://d/v4.0.0/libpod/images/pull?reference=$IMAGE"
fi

if [ $TOOLS_BIN_PATH == "/opt/chipster/tools" ]; then
    TOOLS_BIN_PATH="/mnt/tools"
fi

json="{
    \"command\": [
        \"java\",
        \"-cp\",
        \"lib/*\",
        \"fi.csc.chipster.comp.SingleShotComp\",
        \"$SESSION_ID\",
        \"$JOB_ID\",
        \"$SESSION_TOKEN\"
    ],
    \"env\": {
        \"url_int_service_locator\": \"http://host.containers.internal:8003\"
    },
    \"image\": \"$IMAGE\",
    \"name\": \"$POD_NAME\",
    \"netns\": {
        \"nsmode\": \"bridge\"
    },
    \"resource_limits\": {
        \"memory\": {
            \"limit\": ${POD_MEMORY}000000000
        }
    }
}"

if [ -n "$TOOLS_BIN_NAME" ]; then

    # pod_patch="$pod_patch |
    pod_patch=".mounts[0]Source = \"$TOOLS_BIN_HOST_MOUNT_PATH/$TOOLS_BIN_NAME\" |
        .mounts[0].Destination = \"$TOOLS_BIN_PATH\" |
        .mounts[0].ReadOnly = true |
        .mounts[0].Type = \"bind\""

    json=$(echo "$json" | jq "$pod_patch")
fi


# echo "$json"

echo "$json" | bash -c "$ENV_PREFIX_PODMAN_SOCKET -s -XPOST -H content-type:application/json http://d/v4.0.0/libpod/containers/create --data @-"

bash -c "$ENV_PREFIX_PODMAN_SOCKET -s -XPOST -H content-type:application/json http://d/v4.0.0/libpod/containers/$POD_NAME/start"
