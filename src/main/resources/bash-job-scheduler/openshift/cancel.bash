# no need reserve the thread pool with wait
kubectl delete pod $POD_NAME --wait=false

if [ -n "$STORAGE" ]; then
  kubectl delete pvc $POD_NAME --wait=false
fi
