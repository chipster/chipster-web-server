import {
  isDeadState,
  isProcessAlive,
  parseParentPid,
  parseProcessState,
  startParentMonitor,
  PARENT_PID_ENV,
} from "./parent-monitor.js";
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { spawn, ChildProcess } from "child_process";
import fs from "fs";
import os from "os";
import path from "path";

// keep the tests quick, the production interval is much longer
const TEST_INTERVAL_MS = 10;

/*
Start a process that does nothing until it's killed

A real process is needed to test isProcessAlive(): a made up pid could belong
to anything, and the pid of an exited process is only free after its parent has
reaped it.
*/
function startDummyProcess(): ChildProcess {
  // node is certainly installed, because it's running these tests
  return spawn(
    "node",
    ["-e", "setInterval(() => {}, 1000)"],
    // the tests print nothing, don't let the child print either
    { stdio: "ignore" },
  );
}

/**
 * Kill a process and wait until its pid is free
 *
 * @param child
 */
function killAndWait(child: ChildProcess): Promise<void> {
  return new Promise((resolve) => {
    // the pid is reserved for the exit status until the parent has reaped it,
    // which node does before it emits this event
    child.on("exit", () => resolve());
    child.kill("SIGKILL");
  });
}

/*
Start a process and turn it into a zombie

Node reaps its own children as soon as they exit, so the zombie has to be a
grandchild: the shell starts and kills it, and then replaces itself with a
sleep, which never waits for anything. Returns the shell (now the sleep), which
has to be killed to let the zombie go, and the pid of the zombie. The pid is
null if the zombie didn't appear, because a shell can also reap its children
before the exec.
*/
function startZombie(): Promise<{ parent: ChildProcess; pid: number | null }> {
  return new Promise((resolve) => {
    const parent = spawn("sh", ["-c", "sleep 100 & echo $!; kill -9 $!; exec sleep 100"], {
      stdio: ["ignore", "pipe", "ignore"],
    });
    parent.stdout.once("data", (data) => {
      const pid = Number(data.toString());
      // give the kill a moment to take effect
      setTimeout(() => {
        let state: string | null = null;
        try {
          state = parseProcessState(fs.readFileSync("/proc/" + pid + "/stat", "utf8"));
        } catch (err) {
          // no /proc: there is no way to tell, let the test skip
        }
        resolve({ parent, pid: state === "Z" ? pid : null });
      }, 100);
    });
  });
}

describe("Test parent pid parsing", () => {
  it("accept a pid", () => {
    assert.equal(parseParentPid("1234"), 1234);
  });

  it("reject values that aren't pids", () => {
    // 0 and negative numbers would make process.kill() signal several processes
    let values = [undefined, "", "  ", "abc", "12a", "0", "-1", "1.5", "NaN"];

    for (let value of values) {
      assert.equal(parseParentPid(value), null, "value '" + value + "'");
    }
  });
});

describe("Test process state parsing", () => {
  it("parse the state", () => {
    assert.equal(parseProcessState("1234 (java) S 1 1234 1234 0 -1"), "S");
    assert.equal(parseProcessState("1234 (java) Z 1 1234 1234 0 -1"), "Z");
  });

  it("skip parentheses and spaces in the command name", () => {
    assert.equal(parseProcessState("1234 (my (odd) name) R 1 1234"), "R");
  });

  it("return null for unexpected contents", () => {
    assert.equal(parseProcessState(""), null);
    assert.equal(parseProcessState("1234 java S"), null);
    assert.equal(parseProcessState("1234 (java)"), null);
  });

  it("know the dead states", () => {
    assert.equal(isDeadState("Z"), true);
    assert.equal(isDeadState("X"), true);
    assert.equal(isDeadState("x"), true);

    for (let state of ["R", "S", "D", "T", "t", null]) {
      assert.equal(isDeadState(state), false, "state " + state);
    }
  });
});

describe("Test process checking", () => {
  it("find a running process", () => {
    assert.equal(isProcessAlive(process.pid), true);
  });

  it("notice that a process has exited", async () => {
    const dummy = startDummyProcess();

    assert.equal(isProcessAlive(dummy.pid), true);

    await killAndWait(dummy);

    assert.equal(isProcessAlive(dummy.pid), false);
  });

  it("treat a zombie as gone", async (t) => {
    const zombie = await startZombie();

    try {
      if (zombie.pid == null) {
        t.skip("couldn't make a zombie on this platform");
        return;
      }

      // kill() alone would find the zombie and call it alive
      assert.equal(isProcessAlive(zombie.pid), false);
    } finally {
      await killAndWait(zombie.parent);
    }
  });
});

/*
Run the monitor in a real process and let its parent die

The default of onParentGone() exits the process, so it can't be tested in this
one. This runs the compiled monitor like the service does: with the log file
configured and something keeping the event loop alive, like the servers do.
Returns the exit code and the log file, which is written to a temporary
directory, because Logger writes it relative to the working directory.
*/
function runMonitorUntilParentExits(): Promise<{
  code: number | null;
  log: string;
}> {
  return new Promise((resolve) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "parent-monitor-test-"));
    // the tests run the compiled code, so the child can import it from here
    const monitor = new URL("./parent-monitor.js", import.meta.url).href;
    const logger = new URL("../node_modules/chipster-nodejs-core/lib/logger.js", import.meta.url).href;

    const parent = startDummyProcess();

    const child = spawn(
      process.execPath,
      [
        "--input-type=module",
        "-e",
        `import { startParentMonitor } from "${monitor}";
         import { Logger } from "${logger}";
         Logger.addLogFile();
         startParentMonitor({ intervalMs: 20 });
         // the servers keep the loop alive in the real service
         setInterval(() => {}, 1000);`,
      ],
      { cwd: dir, env: { ...process.env, [PARENT_PID_ENV]: "" + parent.pid } },
    );

    child.on("exit", (code) => {
      let log = "";
      try {
        log = fs.readFileSync(path.join(dir, "logs", "chipster.log"), "utf8");
      } catch (err) {
        // leave it empty, the test reports it
      }
      fs.rmSync(dir, { recursive: true, force: true });
      resolve({ code, log });
    });

    // let the monitor start before taking its parent away
    setTimeout(() => killAndWait(parent), 500);
  });
}

describe("Test parent monitor", () => {
  it("do nothing when the pid isn't set", () => {
    assert.equal(startParentMonitor({ env: {} }), null);
  });

  it("do nothing when the pid isn't valid", () => {
    // a wrong value must not stop the service, it would be running fine
    assert.equal(startParentMonitor({ env: { [PARENT_PID_ENV]: "abc" } }), null);
  });

  it("exit when the parent exits", async () => {
    const dummy = startDummyProcess();

    const parentGone = new Promise<void>((resolve) => {
      const timer = startParentMonitor({
        env: { [PARENT_PID_ENV]: "" + dummy.pid },
        intervalMs: TEST_INTERVAL_MS,
        onParentGone: resolve,
      });

      assert.notEqual(timer, null);
      // the production timer is unref'd, which would let the test process exit
      timer.ref();
    });

    await killAndWait(dummy);
    await parentGone;
  });

  it("keep running while the parent is alive", async () => {
    const dummy = startDummyProcess();

    let calls = 0;

    const timer = startParentMonitor({
      env: { [PARENT_PID_ENV]: "" + dummy.pid },
      intervalMs: TEST_INTERVAL_MS,
      onParentGone: () => calls++,
    });

    // let the monitor poll a few times
    await new Promise((resolve) => setTimeout(resolve, 10 * TEST_INTERVAL_MS));

    assert.equal(calls, 0);

    clearInterval(timer);
    await killAndWait(dummy);
  });

  it("exit a real process when its real parent exits", async () => {
    const result = await runMonitorUntilParentExits();

    // a clean exit, not a crash on the way out
    assert.equal(result.code, 0);
    /* The monitor really ran in that process. Its last message isn't checked,
    because writing it isn't guaranteed, see startParentMonitor(). */
    assert.match(result.log, /monitoring the parent process/);
  });

  it("exit when the parent is already gone", async () => {
    const dummy = startDummyProcess();
    await killAndWait(dummy);

    let calls = 0;

    // no timer, because there is nothing left to wait for
    assert.equal(
      startParentMonitor({
        env: { [PARENT_PID_ENV]: "" + dummy.pid },
        intervalMs: TEST_INTERVAL_MS,
        onParentGone: () => calls++,
      }),
      null,
    );

    assert.equal(calls, 1);
  });
});
