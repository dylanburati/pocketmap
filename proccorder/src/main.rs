use std::borrow::Cow;
use std::convert::TryInto;
use std::fs::{OpenOptions, File};
use std::io::Write;
use std::ops::DerefMut;
use std::process::{Command, Stdio};
use std::sync::{Mutex, Arc};
use std::sync::mpsc::channel;
use std::time::{Duration, Instant};
use anyhow::{anyhow, bail};
use base64::prelude::*;

#[cfg_attr(target_os = "macos", path = "collector/macos.rs")]
#[cfg_attr(target_os = "linux", path = "collector/linux.rs")]
#[cfg_attr(target_os = "windows", path = "collector/windows.rs")]
#[allow(unused_attributes)]
mod collector;

mod types;

enum Output {
    Stdout,
    File(String),
}

enum Writeable {
    Stdout,
    File(File),
}

impl Output {
    fn writer(&self) -> std::io::Result<Writeable> {
        match self {
            Self::Stdout => Ok(Writeable::Stdout),
            Self::File(p) => {
                let f = OpenOptions::new().write(true).create_new(true).open(p)?;
                Ok(Writeable::File(f))
            }
        }
    }
}

impl Write for Writeable {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        match self {
            Writeable::Stdout => std::io::stdout().write(buf),
            Writeable::File(f) => f.write(buf),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        match self {
            Writeable::Stdout => std::io::stdout().flush(),
            Writeable::File(f) => f.flush(),
        }
    }
}

struct Flags {
    /// Milliseconds to wait between process data collection
    freq: u64,
    /// True if the process of interest will be the *first child* of the one spawned by
    /// the command. On Windows, the python.exe in a venv/Scripts dir has this behavior.
    use_grandchild: bool,
    /// Number of runs to perform
    iters: usize,
    /// Location to write recording
    output: Output,
}

impl Default for Flags {
    fn default() -> Self {
        Self { freq: 500, use_grandchild: false, iters: 1, output: Output::Stdout }
    }
}

pub fn main() -> anyhow::Result<()> {
    let mut options_done = false;
    let mut all_args = vec![];
    let mut flags = Flags::default();
    for a in std::env::args().skip(1) {
        if !options_done {
            match a.strip_prefix("-") {
                Some("-") => {
                    options_done = true;
                }
                Some("c") => {
                    flags.use_grandchild = true;
                }
                Some(opt) => {
                    let (name, val) = opt.split_once('=').ok_or(anyhow!("expected option of -k=v form"))?;
                    match name {
                        "freq" => {
                            flags.freq = val.parse()?;
                            if flags.freq < 10 {
                                bail!("frequency can't be below 10 ms");
                            }
                        }
                        "iters" => {
                            flags.iters = val.parse()?;
                            if flags.iters == 0 {
                                bail!("iters must be positive");
                            }
                        }
                        "o" => {
                            flags.output = Output::File(val.to_owned());
                        }
                        _ => bail!("valid options are '-freq=<millis>'; got {}", a),
                    }
                }
                None => {
                    all_args.push(a);
                    options_done = true;
                }
            }
        } else {
            all_args.push(a);
        }
    }

    let (cmd, args) = all_args.split_first().ok_or(anyhow!("Args are required"))?;
    let wr = flags.output.writer()?;
    let mut wr_shared = Arc::new(Mutex::new(wr));
    for _ in 0..flags.iters {
        spawn_and_collect(cmd, args, &flags, &mut wr_shared)?;
    }

    Ok(())
}

fn spawn_and_collect(cmd: &str, args: &[String], flags: &Flags, wr_shared: &mut Arc<Mutex<Writeable>>) -> anyhow::Result<()> {
    let t0 = Instant::now();
    let child = Command::new(cmd)
        .args(args)
        .stdin(Stdio::null())
        .stderr(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()?;
    let mut pid: i32 = child.id().try_into().unwrap();
    if flags.use_grandchild {
        let grandchild = collector::first_child(pid).map_err(|_| anyhow!("Unknown error finding child process"))?;
        if let Some(gc) = grandchild {
            pid = gc;
        } else {
            for i in 0..3 {
                std::thread::sleep(Duration::from_millis(100));
                let grandchild = collector::first_child(pid).map_err(|_| anyhow!("Unknown error finding child process"))?;
                if let Some(gc) = grandchild {
                    pid = gc;
                    break;
                } else if i == 2 {
                    bail!("Timed out finding child process");
                }
            }
        }
    }

    let (sender, receiver) = channel();
    let receiver_shared = Mutex::new(receiver);
    let wr_shared_ref = Arc::clone(&wr_shared);
    let child_output = std::thread::scope(|s| -> anyhow::Result<std::process::Output> {
        s.spawn(move || {
            let mut wr = wr_shared_ref.lock().unwrap();
            let receiver = receiver_shared.lock().unwrap();
            loop {
                match receiver.recv_timeout(Duration::from_millis(flags.freq)) {
                    Ok(_) => { return () },
                    Err(_) => {
                        let mets = collector::collect(pid);
                        serde_json::to_writer(wr.deref_mut(), &mets).unwrap();
                        wr.write(b"\n").unwrap();
                    },
                }
            }
        });
        let child_output = child.wait_with_output()?;
        sender.send(())?;
        Ok(child_output)
    })?;

    let mut wr = wr_shared.lock().unwrap();
    let (stdout_enc, stdout_str) = match std::str::from_utf8(&child_output.stdout) {
        Ok(s) => ("utf8", Cow::Borrowed(s)),
        _ => ("base64", BASE64_STANDARD.encode(&child_output.stdout).into()),
    };
    let (stderr_enc, stderr_str) = match std::str::from_utf8(&child_output.stderr) {
        Ok(s) => ("utf8", Cow::Borrowed(s)),
        _ => ("base64", BASE64_STANDARD.encode(&child_output.stderr).into()),
    };
    let elapsed = t0.elapsed().as_secs_f64();
    serde_json::to_writer(wr.deref_mut(), &serde_json::json!({
        "stdout": {"encoding": stdout_enc, "data": stdout_str},
        "stderr": {"encoding": stderr_enc, "data": stderr_str},
        "elapsed": elapsed
    }))?;
    wr.write(b"\n")?;
    if !child_output.status.success() {
        bail!("{} {} exited with {}", cmd, args.join(" "), child_output.status)
    }
    Ok(())
}
