# Kill the process group of the job, i.e. java and the tool script it runs, by the id that
# run.bash saved, like the pod would be deleted after exceeding storage limit. The group has ended
# already when the job completed by itself. The same block is in cancel.bash.
pgid_file="logs/SingleShotComp-run-${POD_NAME}.pgid"
if [ -s "$pgid_file" ]; then
    pgid=$(cat "$pgid_file")
    if kill -0 -- -$pgid 2> /dev/null; then
        echo "kill process group $pgid"
        kill -- -$pgid
    else
        echo "process group $pgid has ended already"
    fi
else
    echo "no $pgid_file, the job hasn't started"
fi

rm "$pgid_file" logs/SingleShotComp-run-${POD_NAME}.log 2> /dev/null || true
