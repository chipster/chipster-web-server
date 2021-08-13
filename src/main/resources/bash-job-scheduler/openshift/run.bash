echo "start container, slots: $SLOTS, image: $IMAGE, sessionId: $SESSION_ID, jobId: $JOB_ID"
job_id=$(echo comp-job-$SESSION_ID-$JOB_ID | cut -c 1-63)
if kubectl get pod $job_id > /dev/null 2>&1; then
  echo "pod exists already"
  exit 1
fi
memory=$(( SLOTS*8 ))Gi
cpu=$(( SLOTS*2 ))
openshift_image="docker-registry.default.svc:5000/$OPENSHIFT_BUILD_NAMESPACE/$IMAGE"
echo "project: $project, image: $openshift_image"
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
    image: $openshift_image
    imagePullPolicy: IfNotPresent
    command: ["java",  "-cp", "lib/*:", "fi.csc.chipster.comp.SingleShotComp", "$SESSION_ID", "$JOB_ID", "$SESSION_TOKEN", "$COMP_TOKEN"]
    resources:
      limits:
        cpu: $cpu
        memory: $memory
      requests:
        cpu: $cpu
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
  - name: tools-bin
    persistentVolumeClaim:
      claimName: tools-bin-chipster-3.16.6
  - emptyDir: {}
    name: jobs-data  
  restartPolicy: Never
EOF
)
#echo "$job_json"
echo "$job_json" | kubectl apply -f -
