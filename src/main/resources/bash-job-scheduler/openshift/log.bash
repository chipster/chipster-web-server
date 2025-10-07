json=$(kubectl get pod $POD_NAME -o json | jq .status)
short_name=$(echo $JOB_ID | cut -c 1-4)
log="$(kubectl logs $POD_NAME)"

if [ "$json" == null ]; then
    echo "pod $short_name not found"
    exit 1
fi

terminated_reason=$(echo "$json" | jq .containerStatuses[0].state.terminated.reason -r)
if [ "$terminated_reason" != null ]; then              
    if [ "$terminated_reason" == "OOMKilled" ]; then
    echo ""
    echo "The job run out of memory (RAM). If you tried to analyse multiple samples in one job, "
    echo "please check if it's possible to run the tool separately for each sample. This is "
    echo "also faster, because multiple jobs can run in parallel. "
    echo ""
    echo "Otherwise, you can adjust the memory limit. Click the button \"Parameters\" and then "
    echo "open its last section \"Computing Resources\". "
    echo ""
    echo "If you are going to run the same tool for multiple samples, run it first for one "
    echo "sample to find out how much memory is needed. Setting a lower memory limit allows "
    echo "multiple jobs to run in parallel. "
    echo ""
    else
    # something unexpected happened. maybe the log has some useful information
    echo "$log"
    fi
    echo "pod $short_name has terminated: $terminated_reason"
    exit 0
fi

waiting_reason=$(echo "$json" | jq .containerStatuses[0].state.waiting.reason -r)
if [ "$waiting_reason" != null ]; then
    # ErrImagePull (there is now log yet)
    echo "pod $short_name is waiting: $waiting_reason"
    exit 0
fi

echo "$log"
