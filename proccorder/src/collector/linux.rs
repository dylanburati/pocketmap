use once_cell::sync::Lazy;
use procfs::prelude::*;
use procfs::process::{LimitValue, Process};

use super::types::Metrics;

static TICKS_PER_SECOND: Lazy<f64> = Lazy::new(|| procfs::ticks_per_second() as f64);
static BOOT_TIME_SECS: Lazy<Option<f64>> = Lazy::new(|| procfs::boot_time_secs().ok().map(|x| x as f64));

pub fn collect(pid: i32) -> Metrics {
    let mut metrics = Metrics::default();
    if let Ok(proc) = Process::new(pid) {
        if let Ok(uptime) = procfs::Uptime::current() {
            metrics.time_seconds = BOOT_TIME_SECS.map(|bts| bts + uptime.uptime);
        }
        if let Ok(stat) = proc.stat() {
            if let Some(bts) = *BOOT_TIME_SECS {
                metrics.start_time_seconds =
                    Some(bts + ((stat.starttime as f64) / *TICKS_PER_SECOND));
            }
            metrics.cpu_seconds_total = Some((stat.utime + stat.stime) as f64 / *TICKS_PER_SECOND);
            metrics.resident_memory_bytes = Some(stat.rss_bytes().get());
            metrics.virtual_memory_bytes = Some(stat.vsize);
            metrics.num_threads = Some(stat.num_threads as u64);
        }
        metrics.open_fds = proc.fd_count().ok().map(|v| v as u64);
        if let Ok(limit) = proc.limits() {
            metrics.max_fds = match limit.max_open_files.soft_limit {
                LimitValue::Value(v) => Some(v),
                LimitValue::Unlimited => Some(0),
            };
            metrics.virtual_memory_max_bytes = match limit.max_address_space.soft_limit {
                LimitValue::Value(v) => Some(v),
                LimitValue::Unlimited => Some(0),
            };
        }
        if let Ok(tasks) = proc.tasks() {
            let mut thread_cpu = vec![];
            for res in tasks {
                let Ok(task) = res else { continue };
                let Ok(stat) = task.stat() else { continue };
                let cpu_seconds_total = (stat.utime + stat.stime) as f64 / *TICKS_PER_SECOND;
                thread_cpu.push((task.tid, cpu_seconds_total));
            }
            metrics.thread_cpu_seconds_total = Some(thread_cpu);
        }
    }
    metrics
}
