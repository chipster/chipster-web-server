echo "start container, slots: $SLOTS, image: $IMAGE, sessionId: $SESSION_ID, jobId: $JOB_ID"
job_id=$(echo comp-job-$SESSION_ID-$JOB_ID | cut -c 1-63)
memory=$(( SLOTS*8 ))Gi
cpu=$(( SLOTS*2 ))
job_json=$(cat << EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: $job_id
spec:
  template:
    spec:
      containers:
      - name: comp-job
        image: $IMAGE
        imagePullPolicy: IfNotPresent
        command: ["java",  "-cp", "lib/*:", "fi.csc.chipster.comp.SingleShotComp", "$SESSION_ID", "$JOB_ID", "$SESSION_TOKEN", "$COMP_TOKEN"]
        # resources:
        #   limits:
        #     cpu: $cpu
        #     memory: $memory
        #   requests:
        #     cpu: $cpu
        #     memory: $memory
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
  backoffLimit: 0
EOF
)
#echo "$job_json"
echo "$job_json" | kubectl --kubeconfig conf/kubeconfig apply -f -