import { Logger } from "chipster-nodejs-core/lib/logger.js";
import { fileURLToPath } from "url";
import fs from "fs";

const logger = Logger.getLogger(fileURLToPath(import.meta.url));

/**
 * Environment variable where the parent process tells its own pid
 *
 * Set by JavascriptService in the Java server when it starts this service as a
 * child process. It's unset when the service runs on its own, for example in
 * its own container in a real deployment, and then there is no parent to
 * monitor.
 */
export const PARENT_PID_ENV = "CHIPSTER_PARENT_PID";

/**
 * How often to check that the parent process is still alive
 *
 * The check is a cheap syscall, but there is no hurry either: this is only
 * needed when the parent was killed in a way that didn't let it stop us, and
 * then the only harm of waiting is that the ports stay reserved a moment
 * longer.
 */
export const PARENT_POLL_INTERVAL_MS = 2000;

export interface ParentMonitorOptions {
  env?: NodeJS.ProcessEnv;
  isAlive?: (pid: number) => boolean;
  intervalMs?: number;
  onParentGone?: () => void;
}

/**
 * Check whether a process is running
 *
 * @param pid
 * @returns false only when the process is known to be gone
 */
export function isProcessAlive(pid: number): boolean {
  return processExists(pid) && !isZombie(pid);
}

/**
 * Check whether a process exists
 *
 * A zombie exists too: it has exited, but its parent hasn't collected the exit
 * status yet, see isZombie().
 *
 * @param pid
 * @returns false only when the process is known to be gone
 */
function processExists(pid: number): boolean {
  try {
    /* Signal 0 isn't sent to the process. It only runs the checks of kill(),
    which tell whether the process exists. */
    process.kill(pid, 0);
    return true;
  } catch (err) {
    /* ESRCH means that there is no such process. EPERM means that it exists,
    but belongs to another user. Report anything else as alive too, because
    stopping the service is the wrong answer to an unexpected error. */
    return (err as NodeJS.ErrnoException).code !== "ESRCH";
  }
}

/**
 * Check whether a process is a zombie
 *
 * A killed process stays in the process table as a zombie until its parent
 * waits for it, and kill() still finds it there. The Java server is usually
 * reaped right away (by Gradle, a shell or an init that waits for orphans), but
 * an init process that doesn't reap, like a plain program as the entrypoint of
 * a container, would leave it there for good and hide its exit from us.
 *
 * Only Linux tells the state of another process, in /proc. Elsewhere this
 * can't be checked, so a zombie parent is treated as alive there.
 *
 * @param pid
 * @returns true only when the process is known to be a zombie
 */
function isZombie(pid: number): boolean {
  let stat: string;

  try {
    stat = fs.readFileSync("/proc/" + pid + "/stat", "utf8");
  } catch {
    // no /proc, or the process exited after processExists(): the next poll
    // will notice that
    return false;
  }

  return isDeadState(parseProcessState(stat));
}

/**
 * Parse the state field from the contents of /proc/<pid>/stat
 *
 * @param stat contents of the file
 * @returns the state letter, or null if it can't be found
 */
export function parseProcessState(stat: string): string | null {
  /* The fields are separated by spaces, but the second field is the command
  name in parentheses, and it can itself contain spaces and parentheses. The
  last closing parenthesis ends it, and the state is the field after that. */
  const nameEnd = stat.lastIndexOf(")");

  if (nameEnd === -1) {
    return null;
  }

  const fields = stat
    .substring(nameEnd + 1)
    .trim()
    .split(/\s+/);

  return fields[0] || null;
}

/**
 * @param state letter from /proc/<pid>/stat
 * @returns true if the process has exited already
 */
export function isDeadState(state: string | null): boolean {
  // Z is a zombie, X (x on older kernels) is dead and about to be removed
  return state === "Z" || state === "X" || state === "x";
}

/**
 * Parse the pid from the environment variable value
 *
 * @param value
 * @returns the pid, or null if there isn't a valid one
 */
export function parseParentPid(value: string | undefined): number | null {
  if (value == null || value.trim() === "") {
    return null;
  }

  const pid = Number(value);

  /* Not just any number: a non-positive pid would make process.kill() signal a
  process group, or every process we are allowed to signal. */
  if (!Number.isInteger(pid) || pid <= 0) {
    return null;
  }

  return pid;
}

/**
 * Exit when the parent process exits
 *
 * The Java server stops this service when it shuts down, but it can't do
 * anything when it's killed with SIGKILL itself. This service would then keep
 * running as an orphan, holding its ports, and the next server start would look
 * fine while the requests were still answered by the old process running the
 * old code.
 *
 * The parent can't be found from this process (npm starts the service through a
 * shell, so our own parent is that shell, which is orphaned just like us), so
 * the Java server has to tell its pid in the environment, see PARENT_PID_ENV.
 *
 * A dead pid can be reused by an unrelated process, which would hide the exit
 * of the parent from us. That only leaves the service running like it does
 * now, so it's not worth the trouble of comparing the start times of the
 * processes. Exiting while the parent is alive would be the harmful mistake,
 * and that can't happen: the pid does exist whenever the check succeeds.
 *
 * @param options for the tests, the defaults are used in production
 * @returns the poll timer, or null if there is no parent to monitor
 */
export function startParentMonitor(options: ParentMonitorOptions = {}): NodeJS.Timeout | null {
  const env = options.env ?? process.env;
  const isAlive = options.isAlive ?? isProcessAlive;
  const intervalMs = options.intervalMs ?? PARENT_POLL_INTERVAL_MS;
  /* The log file is buffered and process.exit() doesn't wait for it, so a
  heavy log backlog can swallow the message below that tells why the service
  stopped. Flushing it would mean ending the logger, and the transports are
  shared by every logger of the process, so the next message from anywhere else
  would throw "write after end" and kill the process before it got to exit
  cleanly. A lost message is the smaller problem, and there is no backlog to
  lose it in unless the service is busy at the very moment its parent dies. */
  const onParentGone = options.onParentGone ?? (() => process.exit(0));

  const value = env[PARENT_PID_ENV];

  if (value == null || value.trim() === "") {
    logger.info(PARENT_PID_ENV + " is not set, not monitoring the parent process");
    return null;
  }

  const pid = parseParentPid(value);

  if (pid == null) {
    logger.warn(PARENT_PID_ENV + " is not a valid pid: '" + value + "', not monitoring the parent process");
    return null;
  }

  if (!isAlive(pid)) {
    // the parent died before we got this far, no need to start polling
    logger.info("parent process " + pid + " is already gone, exiting");
    onParentGone();
    return null;
  }

  logger.info("monitoring the parent process " + pid);

  const timer = setInterval(() => {
    if (!isAlive(pid)) {
      // stop polling, the callback doesn't necessarily exit immediately
      clearInterval(timer);
      logger.info("parent process " + pid + " has exited, exiting too");
      onParentGone();
    }
  }, intervalMs);

  /* Don't keep the process alive just for this. When the servers are closed and
  nothing else is left, there is no service to monitor either. */
  timer.unref();

  return timer;
}
