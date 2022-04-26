echo "start container, slots: $SLOTS, image: $IMAGE, sessionId: $SESSION_ID, jobId: $JOB_ID"
if kubectl get pod $POD_NAME > /dev/null 2>&1; then
  echo "pod exists already"
  exit 1
fi

pod_patch=".metadata.name=\"$POD_NAME\" |
  .spec.containers[0].image=\"$IMAGE\" |
  .spec.containers[0].imagePullPolicy=\"$IMAGE_PULL_POLICY\" |
  .spec.containers[0].command += [\"$SESSION_ID\", \"$JOB_ID\", \"$SESSION_TOKEN\"]"

if [ -n "$TOOLS_BIN_NAME" ]; then  
  pod_patch="$pod_patch |
    .spec.containers[0].volumeMounts += [{\"name\": \"tools-bin\", \"readOnly\": true, \"mountPath\": \"$TOOLS_BIN_PATH\"}]"
    
  if [ -n "$TOOLS_BIN_HOST_MOUNT_PATH" ]; then
    echo "mount tools-bin from hostPath $TOOLS_BIN_HOST_MOUNT_PATH/$TOOLS_BIN_NAME to $TOOLS_BIN_PATH"
    pod_patch="$pod_patch |
      .spec.volumes += [{\"name\": \"tools-bin\", \"hostPath\": { \"path\": \"$TOOLS_BIN_HOST_MOUNT_PATH/$TOOLS_BIN_NAME\", \"type\": \"Directory\"}}]"
  else
    echo "mount tools-bin from PVC $TOOLS_BIN_NAME to $TOOLS_BIN_PATH"
    pod_patch="$pod_patch |
      .spec.volumes += [{\"name\": \"tools-bin\", \"persistentVolumeClaim\": { \"claimName\": \"tools-bin-$TOOLS_BIN_NAME\"}}]"
  fi
else
  echo "do not mount tools-bin for this tool"
fi

if [[ $ENABLE_RESOURCE_LIMITS == "true" ]]; then

  echo "cpu $POD_CPU, memory ${POD_MEMORY}Gi"
  pod_patch="$pod_patch |
    .metadata.name=\"$POD_NAME\" |
    .spec.containers[0].resources.limits.cpu=\"$POD_CPU\" | 
    .spec.containers[0].resources.limits.memory=\"${POD_MEMORY}Gi\" |
    .spec.containers[0].resources.requests.cpu=\"$POD_CPU\" | 
    .spec.containers[0].resources.requests.memory=\"${POD_MEMORY}Gi\""
else
  echo "resource limits are disabled"
fi

if [ -n "$STORAGE" ]; then
  echo "use PVC for working directory: $STORAGE GiB"

  pod_patch="$pod_patch |
    .spec.volumes += [{\"name\": \"jobs-data\", \"persistentVolumeClaim\": { \"claimName\": \"$POD_NAME\"}}] |
    .spec.env += [{\"name\": \"comp_max_storage\", \"value\": \"\" }]"

  pvc_patch=".metadata.name=\"$POD_NAME\" |
    .spec.resources.requests.storage=\"${STORAGE}Gi\" |
    .metadata.annotations.\"volume.beta.kubernetes.io/storage-class\"=\"$STORAGE_CLASS\""
    

  pvc_json=$(echo "$PVC_YAML" | yq e - -o=json | jq "$pvc_patch")

  echo "$pvc_json" | kubectl apply -f -

else

  echo "use emptyDir for working directory"
  pod_patch="$pod_patch |
    .spec.volumes += [{\"name\": \"jobs-data\", \"emptyDir\": {}}]"
fi

job_json=$(echo "$POD_YAML"    | yq e - -o=json | jq "$pod_patch")

echo "$job_json" | kubectl apply -f -

