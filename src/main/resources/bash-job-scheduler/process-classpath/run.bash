# Run the job in a process, like process/run.bash, but with the java and the classpath of the
# running backend instead of a distribution built for each job. BashJobScheduler gives them in
# BACKEND_JAVA and BACKEND_CLASSPATH.
#
# Use this when the backend runs from build/, e.g. with "./gradlew run" or "./gradlew runProxy":
# the "./gradlew clean" of process/run.bash would delete the classes of the running backend. Jobs
# start in seconds and see the same code as the backend, which also means that they run the old code
# until the backend is restarted.

echo "run in process with the backend's classpath, slots: $SLOTS, pod name: $POD_NAME"

log="logs/SingleShotComp-run-${POD_NAME}.log"
pgid_file="logs/SingleShotComp-run-${POD_NAME}.pgid"

# Fail before the log exists: heartbeat.bash fails without it, and the scheduler ends the job when
# the heartbeat has been lost for scheduler-heartbeat-lost-timeout
if [ -z "$BACKEND_JAVA" ] || [ -z "$BACKEND_CLASSPATH" ]; then
    echo "BACKEND_JAVA or BACKEND_CLASSPATH is not set, is the backend older than these scripts?" >&2
    exit 1
fi

echo "[STATE_STARTING]" > "$log"

for prefixed_name in $(env | grep "ENV_PREFIX_" | cut -d "=" -f 1); do
    name="$(echo "$prefixed_name" | sed s/ENV_PREFIX_//)"
    value="${!prefixed_name}"
    export $name="$value"
done

# The classpath goes in CLASSPATH, which java reads when there is no -cp, so that it doesn't have to
# be quoted. POD_NAME is on the command line for heartbeat.bash, which finds the process by it.
# Without -Xmx each job could take a quarter of the memory, next to the backend; comp only streams
# the files, so 512 MB is enough.
#
# With job control (set -m) the background job gets a process group of its own, which the tool
# script that java starts shares. cancel.bash and finished.bash kill the group, because java doesn't
# end the tool script when it's killed. setsid would do the same, but macOS doesn't have it.
set -m
CLASSPATH="$BACKEND_CLASSPATH" "$BACKEND_JAVA" -Xmx512m -Dchipster.pod-name="$POD_NAME" \
    fi.csc.chipster.comp.SingleShotComp "$SESSION_ID" "$JOB_ID" "$SESSION_TOKEN" >> "$log" 2>&1 &
set +m
pid=$!

# The group is java's own only if java leads it. Otherwise it may be the backend's, and killing it
# would kill the backend, so end the job instead: its log goes to the backend's log, and without
# the job log the heartbeat fails.
pgid=$(ps -o pgid= -p $pid | tr -d ' ')
if [ "$pgid" != "$pid" ]; then
    echo "java $pid ended at once or isn't the leader of its process group '$pgid', ending the job" >&2
    cat "$log" >&2
    kill $pid 2> /dev/null
    rm "$log"
    exit 1
fi

echo "$pgid" > "$pgid_file"
echo "[STATE_RUNNING]" >> "$log"
