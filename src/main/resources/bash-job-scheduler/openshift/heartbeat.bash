json=$(kubectl get pod $POD_NAME -o json | jq .status)
short_name=$(echo $JOB_ID | cut -c 1-4)

if [ "$json" == null ]; then
  echo "pod $short_name not found"
  exit 1
fi

running_state=$(echo "$json" | jq .containerStatuses[0].state.running -r)
if [ "$running_state" != null ]; then
  echo "pod $short_name is running"
  exit 0
fi

terminated_reason=$(echo "$json" | jq .containerStatuses[0].state.terminated.reason -r)
if [ "$terminated_reason" != null ]; then
  echo "pod $short_name has terminated: $terminated_reason"
  exit 1
fi

waiting_reason=$(echo "$json" | jq .containerStatuses[0].state.waiting.reason -r)
if [ "$waiting_reason" != null ]; then
  echo "pod $short_name is waiting: $waiting_reason"
  if [ "$waiting_reason" == "ContainerCreating" ]; then
    exit 0
  elif [ "$waiting_reason" == "ErrImagePull" ]; then
    exit 1
  fi
  exit 1
fi

phase=$(echo "$json" | jq .phase -r)
if [ "$phase" == "Pending" ]; then
  phase_reason=$(echo "$json" | jq .conditions[0].reason -r)
  phase_message=$(echo "$json" | jq .conditions[0].message -r)
  echo "pod $short_name is pending: $phase_reason ($phase_message)"
  exit 0
fi

echo "pod $short_name has unknown status: $json"
exit 1