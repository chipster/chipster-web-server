job_id=$(echo comp-job-$SESSION_ID-$JOB_ID | cut -c 1-63)
kubectl --kubeconfig conf/kubeconfig delete job $job_id
