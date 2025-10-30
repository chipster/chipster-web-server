if bash -c "$ENV_PREFIX_PODMAN_SOCKET -s --fail-with-body http://d/v4.0.0/libpod/images/$IMAGE/exists"; then
    echo "image exists: $IMAGE"
else
    echo "pull image $IMAGE"
    bash -c "$ENV_PREFIX_PODMAN_SOCKET -XPOST -s --fail-with-body http://d/v4.0.0/libpod/images/pull?reference=$IMAGE"
fi


# old images have symlink in /opt/chipster/tools and podman doesn't allow mounting on top of it
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

pod_patch="."

if [ -n "$TOOLS_BIN_NAME" ]; then

    pod_patch="$pod_patch |
        .mounts[0].Source = \"$TOOLS_BIN_HOST_MOUNT_PATH/$TOOLS_BIN_NAME\" |
        .mounts[0].Destination = \"$TOOLS_BIN_PATH\" |
        .mounts[0].ReadOnly = true |
        .mounts[0].Type = \"bind\""
fi

# configure environment variables
for prefixed_name in $(env | grep "ENV_PREFIX_" | cut -d "=" -f 1); do
  name="$(echo "$prefixed_name" | sed s/ENV_PREFIX_//)"
  value="${!prefixed_name}"
  pod_patch="$pod_patch |
      .env.\"$name\" = \"$value\""
done

json=$(echo "$json" | jq "$pod_patch")

# echo "$json"

echo "create container"
echo "$json" | bash -c "$ENV_PREFIX_PODMAN_SOCKET -s -XPOST -H content-type:application/json http://d/v4.0.0/libpod/containers/create --data @-"

echo "start container"
bash -c "$ENV_PREFIX_PODMAN_SOCKET -s -XPOST -H content-type:application/json http://d/v4.0.0/libpod/containers/$POD_NAME/start"
