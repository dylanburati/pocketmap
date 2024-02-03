use std::borrow::Cow;
use std::convert::TryInto;
use std::fs::OpenOptions;
use std::io::Write;
use std::process::{Command, Stdio};
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

impl Output {
    fn writer(&self) -> std::io::Result<Box<dyn Write + Send>> {
        match self {
            Self::Stdout => Ok(Box::new(std::io::stdout())),
            Self::File(p) => {
                let f = OpenOptions::new().write(true).create_new(true).open(p)?;
                Ok(Box::new(f))
            }
        }
    }
}

pub fn main() -> anyhow::Result<()> {
    let mut options_done = false;
    let mut all_args = vec![];
    let mut use_grandchild = false;
    let mut freq = 500;
    let mut output = Output::Stdout;
    for a in std::env::args().skip(1) {
        if !options_done {
            match a.strip_prefix("-") {
                Some("-") => {
                    options_done = true;
                }
                Some("c") => {
                    use_grandchild = true;
                }
                Some(opt) => {
                    let (name, val) = opt.split_once('=').ok_or(anyhow!("expected option of -k=v form"))?;
                    match name {
                        "freq" => {
                            freq = val.parse()?;
                            assert!(freq >= 10, "frequency can't be below 10 ms");
                        }
                        "o" => {
                            output = Output::File(val.to_owned());
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
    let t0 = Instant::now();
    let child = Command::new(cmd)
        .args(args)
        .stdin(Stdio::null())
        .stderr(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()?;
    let mut pid: i32 = child.id().try_into().unwrap();
    if use_grandchild {
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
    let mut wr = output.writer()?;
    let handle = std::thread::spawn(move || {
        loop {
            match receiver.recv_timeout(Duration::from_millis(freq)) {
                Ok(_) => { return wr },
                Err(_) => {
                    let mets = collector::collect(pid);
                    serde_json::to_writer(&mut wr, &mets).unwrap();
                    wr.write(b"\n").unwrap();
                },
            }
        }
    });

    let child_output = child.wait_with_output()?;
    sender.send(())?;
    let mut wr = handle.join().map_err(|_| anyhow!("Join failed"))?;
    let (stdout_enc, stdout_str) = match std::str::from_utf8(&child_output.stdout) {
        Ok(s) => ("utf8", Cow::Borrowed(s)),
        _ => ("base64", BASE64_STANDARD.encode(&child_output.stdout).into()),
    };
    let (stderr_enc, stderr_str) = match std::str::from_utf8(&child_output.stderr) {
        Ok(s) => ("utf8", Cow::Borrowed(s)),
        _ => ("base64", BASE64_STANDARD.encode(&child_output.stderr).into()),
    };
    let elapsed = t0.elapsed().as_secs_f64();
    serde_json::to_writer(&mut wr, &serde_json::json!({
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
