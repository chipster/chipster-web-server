echo "start container, slots: $SLOTS, image: $IMAGE, sessionId: $SESSION_ID, jobId: $JOB_ID"
job_id=$(echo comp-job-$SESSION_ID-$JOB_ID | cut -c 1-63)
if kubectl get pod $job_id > /dev/null 2>&1; then
  echo "pod exists already"
  exit 1
fi
memory=$(( SLOTS*8 ))Gi
cpu_limit=$(( SLOTS*2 ))
cpu_request=$SLOTS
job_json=$(cat << EOF
apiVersion: v1
kind: Pod
metadata:
  name: $job_id
  labels:
    comp-job: ""
spec:
  containers:
  - name: comp-job
    image: $IMAGE
    imagePullPolicy: IfNotPresent
    command: ["java",  "-cp", "lib/*:", "fi.csc.chipster.comp.SingleShotComp", "$SESSION_ID", "$JOB_ID", "$SESSION_TOKEN", "$COMP_TOKEN"]
    resources:
      limits:
        cpu: $cpu_limit
        memory: $memory
      requests:
        cpu: $cpu_request
        memory: $memory
    volumeMounts:
    - mountPath: /opt/chipster/conf
      name: conf
      readOnly: true
    - mountPath: /opt/chipster/tools
      name: tools-bin
      readOnly: true
    - mountPath: /opt/chipster/jobs-data
      name: jobs-data
  volumes:
  - name: conf
    secret:
      defaultMode: 420
      secretName: single-shot-comp
  - hostPath:
      path: /mnt/tools-bin
      type: Directory
    name: tools-bin
  - emptyDir: {}
    name: jobs-data  
  restartPolicy: Never
EOF
)
#echo "$job_json"
echo "$job_json" | kubectl apply -f -